package com.unihub.backend.service;

import com.unihub.backend.dto.PaymentGatewayResult;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.payment-gateway.provider", havingValue = "MOCK", matchIfMissing = true)
@Profile("!prod")
public class MockPaymentGatewayClient implements PaymentGatewayClient {

    private final JdbcTemplate jdbc;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentGatewayResult charge(long amount, String key) {
        insert(amount, key, "TXN-" + UUID.randomUUID());
        return find(amount, key).orElseThrow();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PaymentGatewayResult> reconcile(long amount, String key, boolean closeIfMissing) {
        if (closeIfMissing) {
            insert(amount, key, null);
        }
        return find(amount, key);
    }

    private void insert(long amount, String key, String transactionId) {
        jdbc.update("""
                INSERT INTO mock_payment_operations (idempotency_key, amount, transaction_id)
                VALUES (?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, key, amount, transactionId);
    }

    private Optional<PaymentGatewayResult> find(long amount, String key) {
        return jdbc.query("""
                SELECT amount, transaction_id FROM mock_payment_operations WHERE idempotency_key = ?
                """, (row, index) -> {
            if (row.getLong("amount") != amount) {
                throw new IllegalStateException("Provider amount differs from the original payment");
            }
            String transactionId = row.getString("transaction_id");
            return transactionId == null
                    ? PaymentGatewayResult.builder().failureReason(ATTEMPT_CLOSED).build()
                    : PaymentGatewayResult.builder().success(true).transactionId(transactionId).build();
        }, key).stream().findFirst();
    }
}
