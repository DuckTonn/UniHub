package com.unihub.backend.service;

import com.unihub.backend.dto.RoomRequest;
import com.unihub.backend.dto.WorkshopRequest;
import com.unihub.backend.exception.ConflictException;
import com.unihub.backend.exception.SeatUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class WorkshopRegistrationConcurrencyTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RoomService rooms;
    @Autowired private WorkshopService workshops;
    @Autowired private RegistrationService registrations;
    @Autowired private TransactionTemplate transaction;

    private Long roomId;
    private LocalDateTime start;
    private List<Long> students;

    @BeforeEach
    void createFixtureRoom() {
        roomId = rooms.createRoom(new RoomRequest("Phase 3 " + UUID.randomUUID(), 4)).getId();
        start = LocalDateTime.now().plusDays(3).toLocalDate().atTime(9, 0);
        students = jdbc.queryForList("""
                SELECT id FROM users WHERE role = 'STUDENT' AND status = 'ACTIVE' ORDER BY id LIMIT 2
                """, Long.class);
    }

    @Test
    void onlyOneConcurrentRegistrationCanTakeTheLastSeat() throws Exception {
        Long id = publish(0L);

        var outcomes = concurrently(() -> register(id, students.get(0)), () -> register(id, students.get(1)));

        assertThat(outcomes).containsExactlyInAnyOrder(true, false);
        assertThat(workshops.getPublishedWorkshopById(id).getRemainingSlots()).isZero();
        assertThat(countActiveRegistrations(id)).isEqualTo(1);
    }

    @Test
    void retryingTheSameRegistrationDoesNotConsumeAnotherSeat() throws Exception {
        Long id = publish(0L);
        var results = concurrently(() -> registerOnce(id), () -> registerOnce(id));

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(countActiveRegistrations(id)).isEqualTo(1);
    }

    @Test
    void rollbackLeavesTheLastSeatAvailable() {
        Long id = publish(0L);
        transaction.executeWithoutResult(status -> {
            registrations.register(id, students.get(0));
            status.setRollbackOnly();
        });

        assertThat(countActiveRegistrations(id)).isZero();
        assertThat(workshops.getPublishedWorkshopById(id).getRemainingSlots()).isEqualTo(1);
        registrations.register(id, students.get(1));
        assertThat(countActiveRegistrations(id)).isEqualTo(1);
    }

    @Test
    void expiringTheSamePendingHoldTwiceReleasesOnlyOneSeat() throws Exception {
        Long id = publish(10_000L);
        var hold = registrations.register(id, students.get(0));
        assertThat(hold.isPaidFlow()).isTrue();
        assertThatThrownBy(() -> registrations.register(id, students.get(1)))
                .isInstanceOf(SeatUnavailableException.class);
        Long registrationId = jdbc.queryForObject("""
                UPDATE registrations SET created_at = created_at - interval '11 minutes'
                WHERE workshop_id = ? RETURNING id
                """, Long.class, id);

        var cutoff = LocalDateTime.now().minus(RegistrationService.HOLD_TTL);
        concurrently(() -> expire(registrationId, cutoff), () -> expire(registrationId, cutoff));
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE registration_id = ?",
                String.class, registrationId)).isEqualTo("CANCELLED");
        assertThat(workshops.getPublishedWorkshopById(id).getRemainingSlots()).isEqualTo(1);
        registrations.register(id, students.get(1));
        assertThat(countActiveRegistrations(id)).isEqualTo(1);
        assertThat(workshops.getPublishedWorkshopById(id).getRemainingSlots()).isZero();
    }

    @Test
    void rejectsConcurrentOverlappingPublishesAndAllowsAdjacentWorkshops() throws Exception {
        Long first = workshops.createWorkshop(request(start, 0L)).getId();
        Long second = workshops.createWorkshop(request(start.plusMinutes(30), 0L)).getId();

        var outcomes = concurrently(() -> tryPublish(first), () -> tryPublish(second));
        assertThat(outcomes).containsExactlyInAnyOrder(true, false);

        Long winner = outcomes.get(0) ? first : second;
        var end = workshops.getWorkshopById(winner).getEndTime();
        Long adjacent = workshops.createWorkshop(request(end, 0L)).getId();
        workshops.publishWorkshop(adjacent);
        assertThatThrownBy(() -> workshops.updateWorkshop(adjacent, request(end.minusMinutes(1), 0L)))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> rooms.updateRoom(roomId, new RoomRequest("Changed capacity", 2)))
                .isInstanceOf(ConflictException.class);
    }

    private WorkshopRequest request(LocalDateTime startsAt, long price) {
        return new WorkshopRequest("Phase 3 workshop " + UUID.randomUUID(), null, roomId, null, 1, price,
                startsAt, startsAt.plusHours(1), startsAt.minusDays(5), startsAt.minusDays(1));
    }

    private Long publish(long price) {
        Long id = workshops.createWorkshop(request(start, price)).getId();
        workshops.publishWorkshop(id);
        return id;
    }

    private boolean register(Long workshopId, Long userId) {
        try {
            registrations.register(workshopId, userId);
            return true;
        } catch (SeatUnavailableException exception) {
            return false;
        }
    }

    private boolean registerOnce(Long workshopId) {
        try {
            registrations.register(workshopId, students.getFirst());
            return true;
        } catch (ConflictException exception) {
            assertThat(exception).hasMessageContaining("already has an active registration");
            return false;
        }
    }

    private boolean tryPublish(Long workshopId) {
        try {
            workshops.publishWorkshop(workshopId);
            return true;
        } catch (ConflictException exception) {
            return false;
        }
    }

    private boolean expire(Long id, LocalDateTime cutoff) {
        registrations.expirePendingRegistration(id, cutoff);
        return true;
    }

    private int countActiveRegistrations(Long workshopId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM registrations WHERE workshop_id = ? AND status IN ('PENDING', 'SUCCESS')
                """, Integer.class, workshopId);
    }

    private List<Boolean> concurrently(Callable<Boolean> first, Callable<Boolean> second) throws Exception {
        var ready = new CountDownLatch(2);
        var startTogether = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> runTogether(ready, startTogether, first));
            var b = executor.submit(() -> runTogether(ready, startTogether, second));
            boolean bothReady = ready.await(5, TimeUnit.SECONDS);
            startTogether.countDown();
            assertThat(bothReady).isTrue();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }

    private boolean runTogether(CountDownLatch ready, CountDownLatch startTogether, Callable<Boolean> task)
            throws Exception {
        ready.countDown();
        if (!startTogether.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for the other transaction");
        }
        return task.call();
    }
}
