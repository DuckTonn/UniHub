package com.unihub.backend.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class OperationsMetrics {
    private final JdbcTemplate jdbc;
    private volatile Snapshot snapshot = new Snapshot(0, 0, 0, 0);

    public OperationsMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("unihub.ai.jobs.active", this, m -> m.snapshot.aiJobs()).register(registry);
        Gauge.builder("unihub.outbox.pending", this, m -> m.snapshot.outbox()).register(registry);
        Gauge.builder("unihub.payments.processing", this, m -> m.snapshot.payments()).register(registry);
        Gauge.builder("unihub.operations.last.refresh", this, m -> m.snapshot.updatedAt())
                .baseUnit("seconds").register(registry);
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 2_000)
    public void refresh() {
        snapshot = jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM workshop_ai_documents WHERE status IN ('QUEUED','PROCESSING')),
                       (SELECT count(*) FROM registration_notification_outbox WHERE published_at IS NULL),
                       (SELECT count(*) FROM payments WHERE status = 'PROCESSING')
                """, (row, index) ->
                new Snapshot(row.getLong(1), row.getLong(2), row.getLong(3), Instant.now().getEpochSecond()));
    }

    private record Snapshot(long aiJobs, long outbox, long payments, long updatedAt) {}
}
