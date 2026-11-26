package com.unihub.backend.service;

import com.unihub.backend.dto.CheckinSyncRequest;
import com.unihub.backend.dto.RoomRequest;
import com.unihub.backend.dto.WorkshopRequest;
import com.unihub.backend.exception.ConflictException;
import com.unihub.backend.exception.ResourceNotFoundException;
import com.unihub.backend.exception.SeatUnavailableException;
import com.unihub.backend.scheduler.PendingRegistrationCancellationScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentAttendanceIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RoomService rooms;
    @Autowired private WorkshopService workshops;
    @Autowired private RegistrationService registrations;
    @Autowired private PaymentService payments;
    @Autowired private PaymentGatewayClient provider;
    @Autowired private CheckinService checkins;
    @Autowired private TransactionTemplate transaction;
    @Autowired private PendingRegistrationCancellationScheduler scheduler;

    private Long workshopId;
    private List<Long> students;

    @BeforeEach
    void createWorkshop() {
        Long room = rooms.createRoom(new RoomRequest("Payment and attendance " + UUID.randomUUID(), 2)).getId();
        var start = LocalDateTime.now().plusDays(3).withNano(0);
        workshopId = workshops.createWorkshop(new WorkshopRequest("Phase 4 " + UUID.randomUUID(),
                null, room, null, 1, 10_000L, start, start.plusHours(1),
                start.minusDays(5), start.minusDays(1))).getId();
        workshops.publishWorkshop(workshopId);
        students = jdbc.queryForList("""
                SELECT id FROM users WHERE role = 'STUDENT' AND status = 'ACTIVE' ORDER BY id LIMIT 2
                """, Long.class);
    }

    @Test
    void concurrentRetriesReturnTheSameTransactionAndQrAndRejectAnotherOwner() throws Exception {
        var student = students.getFirst();
        String key = registrations.register(workshopId, student).getIdempotencyKey();
        assertThatThrownBy(() -> payments.pay(workshopId, students.getLast(), key))
                .isInstanceOf(ResourceNotFoundException.class);

        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return payments.pay(workshopId, student, key);
            });
            var second = executor.submit(() -> {
                start.await();
                return payments.pay(workshopId, student, key);
            });
            start.countDown();
            var result = first.get(15, TimeUnit.SECONDS);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(result);
            assertThat(result.getQrCode()).startsWith("QR-");
            assertThat(provider.reconcile(10_000L, key, false).orElseThrow().getTransactionId())
                    .isEqualTo(result.getTransactionId());
        }
    }

    @Test
    void reconciliationFindsAChargeAfterTheApplicationTransactionRollsBack() {
        var student = students.getFirst();
        String key = registrations.register(workshopId, student).getIdempotencyKey();
        var attempt = registrations.beginPayment(workshopId, student, key, true);
        var charge = provider.charge(attempt.amount(), attempt.key());
        transaction.executeWithoutResult(status -> {
            registrations.settlePayment(attempt.id(), charge);
            status.setRollbackOnly();
        });
        ageHold();
        scheduler.cancelStalePendingRegistrations();
        assertThatThrownBy(() -> registrations.register(workshopId, students.getLast()))
                .isInstanceOf(SeatUnavailableException.class);

        scheduler.reconcilePayments();
        // Repeated delivery of the same confirmed provider outcome must be harmless.
        var replay = registrations.settlePayment(attempt.id(), charge);
        assertThat(replay.getTransactionId()).isEqualTo(charge.getTransactionId());
        assertThat(replay).isEqualTo(payments.pay(workshopId, student, key));
        assertThat(replay.getQrCode()).startsWith("QR-");
    }

    @Test
    void reconciliationClosesAnExpiredUnchargedAttemptAgainstLateDelivery() {
        var student = students.getFirst();
        String key = registrations.register(workshopId, student).getIdempotencyKey();
        var attempt = registrations.beginPayment(workshopId, student, key, true);
        payments.reconcile(attempt.id());
        assertThat(provider.reconcile(attempt.amount(), attempt.key(), false)).isEmpty();
        ageHold();

        payments.reconcile(attempt.id());
        assertThat(provider.charge(attempt.amount(), attempt.key()).getFailureReason())
                .isEqualTo(PaymentGatewayClient.ATTEMPT_CLOSED);
        assertThatThrownBy(() -> payments.pay(workshopId, student, key)).isInstanceOf(ConflictException.class);
        assertThat(registrations.register(workshopId, students.getLast()).isPaidFlow()).isTrue();
    }

    @Test
    void chargeRecoveredAfterWorkshopCancellationIsFlaggedWithoutAnAdmissionTicket() {
        var student = students.getFirst();
        String key = registrations.register(workshopId, student).getIdempotencyKey();
        var attempt = registrations.beginPayment(workshopId, student, key, true);
        var charge = transaction.execute(status -> {
            workshops.cancelWorkshop(workshopId);
            return provider.charge(attempt.amount(), attempt.key());
        });
        payments.reconcile(attempt.id());
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, attempt.id()))
                .isEqualTo("REFUND_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT transaction_id FROM payments WHERE id = ?", String.class, attempt.id()))
                .isEqualTo(charge.getTransactionId());
        assertThat(jdbc.queryForObject("SELECT status FROM registrations WHERE workshop_id = ?", String.class, workshopId))
                .isEqualTo("CANCELLED");
    }

    @Test
    void replayOfMixedBatchKeepsFirstScanAndReportsFailuresByQr() {
        var student = students.getFirst();
        String key = registrations.register(workshopId, student).getIdempotencyKey();
        String qr = payments.pay(workshopId, student, key).getQrCode();
        var timestamp = LocalDateTime.now().withNano(0);

        var first = checkins.syncCheckins(batch(qr, timestamp));
        assertThat(first.getSuccessCount()).isEqualTo(1);
        assertThat(first.getDuplicateCount()).isZero();
        assertThat(first.getFailures()).extracting("qrCode").containsExactly("unknown-ticket");

        var replay = checkins.syncCheckins(batch(qr, timestamp.minusMinutes(5)));
        assertThat(replay.getSuccessCount()).isZero();
        assertThat(replay.getDuplicateCount()).isEqualTo(1);
        assertThat(replay.getFailedCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT c.scanned_at FROM checkin_records c JOIN registrations r ON r.id = c.registration_id
                WHERE r.workshop_id = ?
                """, LocalDateTime.class, workshopId)).isEqualTo(timestamp);
    }

    private CheckinSyncRequest batch(String qr, LocalDateTime time) {
        return new CheckinSyncRequest(workshopId, List.of(
                new CheckinSyncRequest.CheckinEntry(qr, time),
                new CheckinSyncRequest.CheckinEntry("unknown-ticket", time)));
    }

    private void ageHold() {
        jdbc.update("UPDATE registrations SET created_at = created_at - interval '11 minutes' WHERE workshop_id = ?",
                workshopId);
    }
}
