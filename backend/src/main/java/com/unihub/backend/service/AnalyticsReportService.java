package com.unihub.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.backend.dto.GlobalStatsResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsReportService {
    private final GlobalStatsService stats;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Value("${analytics.projection.enabled:true}")
    private boolean projectionEnabled;

    @Scheduled(fixedDelay = 30_000, initialDelayString = "${analytics.projection.initial-delay:PT2S}")
    @Transactional(isolation = Isolation.REPEATABLE_READ, timeout = 15)
    public void refresh() {
        if (!projectionEnabled || !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(60412502)", Boolean.class))) {
            return;
        }
        var capturedAt = transactionTime();
        var report = stats.calculate(capturedAt.toInstant().toString());
        try {
            jdbc.update("""
                    INSERT INTO analytics_reports (name, captured_at, payload) VALUES ('global-v1', ?, CAST(? AS jsonb))
                    ON CONFLICT (name) DO UPDATE SET captured_at = EXCLUDED.captured_at, payload = EXCLUDED.payload
                    WHERE analytics_reports.captured_at < EXCLUDED.captured_at
                    """, capturedAt, json.writeValueAsString(report));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize analytics report", error);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 15)
    public GlobalStatsResponse read() {
        var capturedAt = transactionTime();
        if (projectionEnabled) {
            var payload = jdbc.queryForList("""
                    SELECT payload::text FROM analytics_reports
                    WHERE name = 'global-v1' AND captured_at >= CURRENT_TIMESTAMP - interval '60 seconds'
                    """, String.class);
            if (!payload.isEmpty()) {
                try {
                    return json.readValue(payload.getFirst(), GlobalStatsResponse.class);
                } catch (JsonProcessingException error) {
                    log.warn("Could not decode analytics projection; calculating from transactional data");
                }
            }
        }
        return stats.calculate(capturedAt.toInstant().toString());
    }

    private OffsetDateTime transactionTime() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", OffsetDateTime.class);
    }
}
