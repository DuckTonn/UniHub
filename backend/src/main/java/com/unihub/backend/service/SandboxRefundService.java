package com.unihub.backend.service;

import com.unihub.backend.exception.ConflictException;
import com.unihub.backend.exception.PaymentGatewayUnavailableException;
import com.unihub.backend.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class SandboxRefundService {
    private static final Logger log = LoggerFactory.getLogger(SandboxRefundService.class);
    private static final int MAX_ATTEMPTS = 5;

    private final JdbcClient jdbc;
    private final boolean accepting;
    private final boolean workerEnabled;
    private final long workshopId;

    public SandboxRefundService(JdbcClient jdbc, Environment environment,
                                @Value("${app.sandbox-refunds.enabled}") boolean enabled,
                                @Value("${app.sandbox-refunds.worker-enabled}") boolean workerEnabled,
                                @Value("${app.sandbox-refunds.workshop-id}") long workshopId) {
        this.jdbc = jdbc;
        boolean mock = !environment.acceptsProfiles(Profiles.of("prod"))
                && "MOCK".equals(environment.getProperty("app.payment-gateway.provider"));
        this.accepting = mock && enabled && workerEnabled;
        this.workerEnabled = mock && workerEnabled;
        this.workshopId = workshopId;
    }

    public boolean availableFor(long id) {
        return accepting && (workshopId == 0 || workshopId == id);
    }

    @Transactional
    public RefundResponse request(long paymentId, long adminId, String key) {
        if (!accepting) {
            throw new PaymentGatewayUnavailableException("Sandbox refunds are disabled");
        }
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new IllegalArgumentException("A valid Idempotency-Key is required");
        }
        var payment = jdbc.sql("""
                SELECT p.amount, p.transaction_id, w.id AS workshop_id,
                       (p.status = 'REFUND_REQUIRED' OR (p.status = 'COMPLETED' AND w.status = 'CANCELLED'))
                       AND EXISTS (SELECT 1 FROM mock_payment_operations m
                                   WHERE m.idempotency_key = p.idempotency_key
                                     AND m.transaction_id = p.transaction_id AND m.amount = p.amount) AS eligible
                FROM payments p JOIN registrations r ON r.id = p.registration_id
                JOIN workshops w ON w.id = r.workshop_id
                WHERE p.id = ? FOR UPDATE OF p
                """).param(paymentId).query((row, i) -> new Charge(row.getLong("amount"),
                row.getString("transaction_id"), row.getLong("workshop_id"), row.getBoolean("eligible")))
                .optional().orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        if (!availableFor(payment.workshopId())) {
            throw new ConflictException("Payment is outside the sandbox workshop scope");
        }
        var existing = findRequest(paymentId, key);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!payment.eligible() || payment.amount() <= 0 || payment.reference().isBlank()) {
            throw new ConflictException("Payment is not eligible for a sandbox refund");
        }

        var id = UUID.randomUUID();
        int inserted = jdbc.sql("""
                INSERT INTO payment_refunds
                    (id, payment_id, amount, currency, idempotency_key, provider_key, charge_reference, status, requested_by)
                VALUES (?, ?, ?, 'VND', ?, ?, ?, 'PENDING', ?) ON CONFLICT DO NOTHING
                """).params(id, paymentId, payment.amount(), key, UUID.randomUUID(), payment.reference(), adminId)
                .update();
        if (inserted == 0) {
            return findRequest(paymentId, key).orElseThrow(() -> new ConflictException("Refund already exists"));
        }
        log.info("Sandbox refund queued: refundId={} paymentId={}", id, paymentId);
        return new RefundResponse(id, "PENDING", null);
    }

    private Optional<RefundResponse> findRequest(long paymentId, String key) {
        return jdbc.sql("""
                SELECT id, status, refund_reference, payment_id, idempotency_key FROM payment_refunds
                WHERE payment_id = ? OR idempotency_key = ?
                """).params(paymentId, key).query((row, i) -> {
            if (row.getLong("payment_id") != paymentId || !key.equals(row.getString("idempotency_key"))) {
                throw new ConflictException("Payment or idempotency key already has a refund");
            }
            return new RefundResponse(row.getObject("id", UUID.class), row.getString("status"),
                    row.getString("refund_reference"));
        }).optional();
    }

    @Scheduled(fixedDelayString = "${app.sandbox-refunds.poll-interval:PT2S}", initialDelayString = "PT2S")
    public void poll() {
        if (workerEnabled) {
            processNext();
        }
    }

    void processNext() {
        claim().ifPresent(job -> {
            try {
                if (job.attempt() <= MAX_ATTEMPTS) {
                    // Independent transaction. A retry can recover the result even if recording it below fails.
                    jdbc.sql("""
                            INSERT INTO mock_refund_operations (provider_key, charge_reference, amount, refund_reference)
                            SELECT :key, transaction_id, amount, :refundReference
                            FROM mock_payment_operations
                            WHERE transaction_id = :charge AND amount = :amount
                              AND EXISTS (SELECT 1 FROM payment_refunds
                                          WHERE id = :id AND attempts = :attempt AND status = 'PROCESSING'
                                          FOR UPDATE)
                            ON CONFLICT DO NOTHING
                            """).param("key", job.providerKey()).param("refundReference", "SANDBOX-" + job.providerKey())
                            .param("charge", job.chargeReference()).param("amount", job.amount())
                            .param("id", job.id()).param("attempt", job.attempt()).update();
                }
                var result = jdbc.sql("""
                        SELECT refund_reference FROM mock_refund_operations
                        WHERE provider_key = ? AND charge_reference = ? AND amount = ?
                        """).params(job.providerKey(), job.chargeReference(), job.amount()).query(String.class).optional();
                if (result.isPresent()) {
                    finish(job, "SUCCEEDED", result.get(), null);
                } else if (job.attempt() > MAX_ATTEMPTS) {
                    finish(job, "PROCESSING", null, "REVIEW_REQUIRED");
                } else {
                    finish(job, "FAILED", null, "MOCK_CHARGE_REJECTED");
                }
            } catch (DataAccessException exception) {
                // The lease expires even if the outcome could not be read or persisted.
                log.warn("Sandbox refund outcome unavailable: refundId={} paymentId={} attempt={}",
                        job.id(), job.paymentId(), job.attempt());
            }
        });
    }

    private Optional<Job> claim() {
        return jdbc.sql("""
                WITH candidate AS (
                    SELECT f.id FROM payment_refunds f
                    JOIN payments p ON p.id = f.payment_id JOIN registrations r ON r.id = p.registration_id
                    WHERE f.status IN ('PENDING','PROCESSING') AND (f.lease_until IS NULL OR f.lease_until <= now())
                      AND f.failure_code IS DISTINCT FROM 'REVIEW_REQUIRED'
                      AND (:workshop = 0 OR r.workshop_id = :workshop)
                    ORDER BY f.created_at, f.id LIMIT 1 FOR UPDATE OF f SKIP LOCKED
                )
                UPDATE payment_refunds f SET status = 'PROCESSING', attempts = f.attempts + 1,
                    lease_until = now() + interval '30 seconds', updated_at = now()
                FROM candidate WHERE f.id = candidate.id
                RETURNING f.id, f.payment_id, f.provider_key, f.charge_reference, f.amount, f.attempts
                """).param("workshop", workshopId).query((row, i) -> new Job(row.getObject("id", UUID.class),
                row.getLong("payment_id"), row.getObject("provider_key", UUID.class),
                row.getString("charge_reference"), row.getLong("amount"), row.getInt("attempts"))).optional();
    }

    private void finish(Job job, String status, String reference, String failureCode) {
        int updated = jdbc.sql("""
                UPDATE payment_refunds SET status = ?, refund_reference = ?, failure_code = ?,
                    lease_until = NULL, updated_at = now() WHERE id = ? AND attempts = ?
                """).params(status, reference, failureCode, job.id(), job.attempt()).update();
        if (updated == 1) {
            log.info("Sandbox refund outcome: refundId={} paymentId={} status={} failureCode={}",
                    job.id(), job.paymentId(), status, failureCode);
        }
    }

    public record RefundResponse(UUID refundId, String status, String refundReference) {}
    private record Charge(long amount, String reference, long workshopId, boolean eligible) {}
    private record Job(UUID id, long paymentId, UUID providerKey, String chargeReference, long amount, int attempt) {}
}
