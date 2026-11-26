package com.unihub.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"ai.documents.worker-enabled=false", "analytics.projection.initial-delay=PT10M"})
class AiAnalyticsIntegrationTest {
    @Autowired private WorkshopAiJobs jobs;
    @Autowired private AnalyticsReportService analytics;
    @Autowired private GlobalStatsService live;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @Transactional
    void expiredAndSupersededWorkersCannotOverwriteManualEdits() {
        long workshop = workshop("DRAFT");
        assertThat(jobs.enqueue(workshop, new byte[] {1})).isTrue();
        var first = jobs.claim().orElseThrow();
        assertThat(jobs.claim()).isEmpty();
        jdbc.update("UPDATE workshop_ai_documents SET available_at = CURRENT_TIMESTAMP WHERE workshop_id = ?", workshop);
        var retried = jobs.claim().orElseThrow();
        assertThat(retried.attempt()).isEqualTo(2);

        jobs.complete(first, "Expired answer", "Old speaker");
        assertThat(jobs.status(workshop).status()).isEqualTo("PROCESSING");
        assertThat(jobs.enqueue(workshop, new byte[] {2})).isTrue();
        var newest = jobs.claim().orElseThrow();
        assertThat(newest.document()).containsExactly((byte) 2);
        jobs.complete(retried, "Superseded answer", "Old speaker");

        jdbc.update("UPDATE workshops SET description = 'Manual description' WHERE id = ?", workshop);
        jobs.complete(newest, "New answer", "Detected speaker");
        assertThat(jdbc.queryForObject("SELECT description FROM workshops WHERE id = ?", String.class, workshop))
                .isEqualTo("Manual description");
        assertThat(jdbc.queryForObject("SELECT speaker FROM workshops WHERE id = ?", String.class, workshop))
                .isEqualTo("Detected speaker");
        assertThat(jobs.status(workshop).status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT document IS NULL FROM workshop_ai_documents WHERE workshop_id = ?",
                Boolean.class, workshop)).isTrue();
    }

    @Test
    @Transactional
    void rebuiltReportMatchesTransactionalDataAndFallsBackWhenStale() {
        jdbc.execute("SELECT pg_advisory_xact_lock(60412502)");
        var baseline = live.calculate(null);
        long workshop = workshop("COMPLETED");
        jdbc.update("""
                INSERT INTO registrations (workshop_id, user_id, qr_code, status)
                SELECT ?, users.id, ?, 'SUCCESS' FROM users ORDER BY users.id LIMIT 1
                """, workshop, UUID.randomUUID().toString());
        jdbc.update("DELETE FROM analytics_reports WHERE name = 'global-v1'");
        analytics.refresh();
        var projected = analytics.read();
        assertThat(projected.getDataAsOf()).isNotBlank();
        assertThat(projected).usingRecursiveComparison().ignoringFields("dataAsOf").isEqualTo(live.calculate(null));
        assertThat(projected.getTotalRegistrations()).isEqualTo(baseline.getTotalRegistrations() + 1);
        assertThat(projected.getActualParticipationRate()).isLessThanOrEqualTo(baseline.getActualParticipationRate());

        jdbc.update("""
                INSERT INTO checkin_records (registration_id, scanned_at)
                SELECT id, CURRENT_TIMESTAMP FROM registrations WHERE workshop_id = ?
                """, workshop);
        assertThat(analytics.read().getActualParticipationRate()).isEqualTo(projected.getActualParticipationRate());
        jdbc.update("UPDATE analytics_reports SET captured_at = CURRENT_TIMESTAMP - interval '2 minutes'");
        var fresh = analytics.read();
        assertThat(fresh).usingRecursiveComparison().ignoringFields("dataAsOf").isEqualTo(live.calculate(null));
        assertThat(fresh.getActualParticipationRate()).isGreaterThan(projected.getActualParticipationRate());
    }

    private long workshop(String status) {
        return jdbc.queryForObject("""
                INSERT INTO workshops (title, description, status, room_id, total_slots, remaining_slots, price,
                    start_time, end_time, registration_start_time, registration_end_time)
                VALUES (?, 'Original description', ?, (SELECT min(id) FROM rooms), 2, 1, 0,
                    now() + interval '3 days', now() + interval '4 days', now(), now() + interval '2 days')
                RETURNING id
                """, Long.class, "Phase 5 " + UUID.randomUUID(), status);
    }
}
