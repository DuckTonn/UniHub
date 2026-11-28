package com.unihub.backend.service;

import com.unihub.backend.dto.RoomRequest;
import com.unihub.backend.dto.WorkshopRequest;
import com.unihub.backend.exception.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "app.sandbox-refunds.enabled=true",
        "app.sandbox-refunds.poll-interval=PT1H"
})
class SandboxRefundIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RoomService rooms;
    @Autowired private WorkshopService workshops;
    @Autowired private RegistrationService registrations;
    @Autowired private PaymentService payments;
    @Autowired private SandboxRefundService refunds;

    @Test
    void concurrentConfirmationAndRecoveryAfterProviderCommitKeepOneRefund() throws Exception {
        long admin = user("ADMIN");
        long payment = paidWorkshop(true);
        String key = UUID.randomUUID().toString();
        SandboxRefundService.RefundResponse accepted;
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return refunds.request(payment, admin, key); });
            var second = executor.submit(() -> { start.await(); return refunds.request(payment, admin, key); });
            start.countDown();
            accepted = first.get(15, TimeUnit.SECONDS);
            assertThat(second.get(15, TimeUnit.SECONDS).refundId()).isEqualTo(accepted.refundId());
        }
        var id = accepted.refundId();
        // The simulator commits before the worker can persist its local outcome.
        jdbc.update("""
                INSERT INTO mock_refund_operations (provider_key, charge_reference, amount, refund_reference)
                SELECT provider_key, charge_reference, amount, 'SANDBOX-' || provider_key
                FROM payment_refunds WHERE id = ? ON CONFLICT DO NOTHING
                """, id);
        jdbc.update("""
                UPDATE payment_refunds SET status = 'PROCESSING', lease_until = now() - interval '1 second',
                    attempts = 5 WHERE id = ?
                """, id);
        refunds.processNext();
        var replay = refunds.request(payment, admin, key);
        assertThat(replay.refundId()).isEqualTo(id);
        assertThat(replay.status()).isEqualTo("SUCCEEDED");
        assertThat(replay.refundReference()).startsWith("SANDBOX-");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM mock_refund_operations m JOIN payment_refunds f ON f.provider_key = m.provider_key
                WHERE f.payment_id = ?
                """, Long.class, payment)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM payments p JOIN payment_refunds f ON f.payment_id = p.id
                WHERE p.id = ? AND p.status = 'COMPLETED' AND p.amount = f.amount AND p.transaction_id = f.charge_reference
                """, Long.class, payment)).isEqualTo(1L);
        assertThatThrownBy(() -> refunds.request(payment, admin, UUID.randomUUID().toString()))
                .isInstanceOf(ConflictException.class);
        long other = paidWorkshop(true);
        assertThatThrownBy(() -> refunds.request(other, admin, key)).isInstanceOf(ConflictException.class);
    }

    @Test
    void rejectsUncancelledAndUnverifiedCharges() {
        long admin = user("ADMIN");
        long payment = paidWorkshop(false);
        assertThatThrownBy(() -> refunds.request(payment, admin, UUID.randomUUID().toString()))
                .isInstanceOf(ConflictException.class);
        jdbc.update("UPDATE payments SET status = 'REFUND_REQUIRED', transaction_id = ? WHERE id = ?",
                "external-" + UUID.randomUUID(), payment);
        assertThatThrownBy(() -> refunds.request(payment, admin, UUID.randomUUID().toString()))
                .isInstanceOf(ConflictException.class);
    }

    private long paidWorkshop(boolean cancel) {
        String name = "Sandbox refund " + UUID.randomUUID();
        long room = rooms.createRoom(new RoomRequest(name, 2)).getId();
        var start = LocalDateTime.now().plusDays(3).withNano(0);
        long workshop = workshops.createWorkshop(new WorkshopRequest(name, null, room, null, 1, 12_000L,
                start, start.plusHours(1), start.minusDays(5), start.minusDays(1))).getId();
        workshops.publishWorkshop(workshop);
        long student = user("STUDENT");
        var key = registrations.register(workshop, student).getIdempotencyKey();
        payments.pay(workshop, student, key);
        if (cancel) {
            workshops.cancelWorkshop(workshop);
        }
        return jdbc.queryForObject("SELECT id FROM payments WHERE idempotency_key = ?", Long.class, key);
    }

    private long user(String role) {
        return jdbc.queryForObject("SELECT id FROM users WHERE role = ? AND status = 'ACTIVE' ORDER BY id LIMIT 1",
                Long.class, role);
    }
}
