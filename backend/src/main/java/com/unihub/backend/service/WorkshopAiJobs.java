package com.unihub.backend.service;

import com.unihub.backend.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WorkshopAiJobs {
    private static final int MAX_ACTIVE_JOBS = 20;

    private final JdbcTemplate jdbc;

    public record Job(long workshopId, long generation, int attempt, byte[] document) {}
    public record Status(String status, String updatedAt, String error) {}
    private record OriginalText(String description, String speaker) {}

    @Transactional
    public boolean enqueue(long workshopId, byte[] document) {
        jdbc.execute("SELECT pg_advisory_xact_lock(60412501)");
        if (jdbc.queryForList("SELECT id FROM workshops WHERE id = ? FOR UPDATE", Long.class, workshopId).isEmpty()) {
            throw new ResourceNotFoundException("Workshop not found");
        }
        Integer otherActiveJobs = jdbc.queryForObject("""
                SELECT count(*) FROM workshop_ai_documents
                WHERE status IN ('QUEUED', 'PROCESSING') AND workshop_id <> ?
                """, Integer.class, workshopId);
        if (otherActiveJobs >= MAX_ACTIVE_JOBS) {
            return false;
        }
        jdbc.update("""
                INSERT INTO workshop_ai_documents
                    (workshop_id, status, document, original_description, original_speaker)
                SELECT id, 'QUEUED', ?, description, speaker FROM workshops WHERE id = ?
                ON CONFLICT (workshop_id) DO UPDATE
                SET generation = EXCLUDED.generation, status = 'QUEUED', document = EXCLUDED.document,
                    original_description = EXCLUDED.original_description, original_speaker = EXCLUDED.original_speaker,
                    attempts = 0, available_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP, error = NULL
                """, document, workshopId);
        return true;
    }

    public Status status(long workshopId) {
        return jdbc.query("""
                SELECT status, updated_at, error FROM workshop_ai_documents WHERE workshop_id = ?
                """, (rs, row) -> new Status(rs.getString("status"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant().toString(), rs.getString("error")),
                workshopId).stream().findFirst().orElseGet(() -> new Status("IDLE", null, null));
    }

    @Transactional
    public Optional<Job> claim() {
        jdbc.update("""
                UPDATE workshop_ai_documents
                SET status = 'FAILED', document = NULL, original_description = NULL, original_speaker = NULL,
                    updated_at = CURRENT_TIMESTAMP, error = 'Document processing did not finish'
                WHERE status = 'PROCESSING' AND attempts >= 3 AND available_at <= CURRENT_TIMESTAMP
                """);
        return jdbc.query("""
                WITH candidate AS (
                    SELECT workshop_id FROM workshop_ai_documents
                    WHERE status IN ('QUEUED', 'PROCESSING') AND available_at <= CURRENT_TIMESTAMP AND attempts < 3
                    ORDER BY available_at, generation LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE workshop_ai_documents AS job
                SET status = 'PROCESSING', attempts = attempts + 1, error = NULL,
                    updated_at = CURRENT_TIMESTAMP, available_at = CURRENT_TIMESTAMP + interval '60 seconds'
                FROM candidate WHERE job.workshop_id = candidate.workshop_id
                RETURNING job.workshop_id, generation, attempts, document
                """, (rs, row) -> new Job(rs.getLong("workshop_id"), rs.getLong("generation"),
                rs.getInt("attempts"), rs.getBytes("document"))).stream().findFirst();
    }

    @Transactional
    public void complete(Job job, String summary, String speaker) {
        // Workshop operations lock this row before interacting with the document row.
        if (jdbc.queryForList("SELECT id FROM workshops WHERE id = ? FOR UPDATE", Long.class, job.workshopId()).isEmpty()) {
            return;
        }
        var original = jdbc.query("""
                SELECT original_description, original_speaker FROM workshop_ai_documents
                WHERE workshop_id = ? AND generation = ? AND attempts = ? AND status = 'PROCESSING'
                FOR UPDATE
                """, (rs, row) -> new OriginalText(rs.getString(1), rs.getString(2)),
                job.workshopId(), job.generation(), job.attempt()).stream().findFirst();
        if (original.isEmpty()) {
            return;
        }
        var baseline = original.get();
        jdbc.update("""
                UPDATE workshops
                SET description = CASE WHEN description IS NOT DISTINCT FROM ? THEN COALESCE(?, description) ELSE description END,
                    speaker = CASE WHEN speaker IS NOT DISTINCT FROM ? THEN COALESCE(?, speaker) ELSE speaker END
                WHERE id = ?
                """, baseline.description(), summary, baseline.speaker(), speaker, job.workshopId());
        jdbc.update("""
                UPDATE workshop_ai_documents
                SET status = 'COMPLETED', document = NULL, original_description = NULL, original_speaker = NULL,
                    updated_at = CURRENT_TIMESTAMP WHERE workshop_id = ?
                """, job.workshopId());
    }

    public void fail(Job job, boolean retryable, String error) {
        if (retryable && job.attempt() < 3) {
            jdbc.update("""
                    UPDATE workshop_ai_documents SET status = 'QUEUED', error = ?,
                      updated_at = CURRENT_TIMESTAMP, available_at = CURRENT_TIMESTAMP + interval '15 seconds'
                    WHERE workshop_id = ? AND generation = ? AND attempts = ? AND status = 'PROCESSING'
                    """, error, job.workshopId(), job.generation(), job.attempt());
        } else {
            jdbc.update("""
                    UPDATE workshop_ai_documents SET status = 'FAILED', error = ?, document = NULL,
                      original_description = NULL, original_speaker = NULL, updated_at = CURRENT_TIMESTAMP
                    WHERE workshop_id = ? AND generation = ? AND attempts = ? AND status = 'PROCESSING'
                    """, error, job.workshopId(), job.generation(), job.attempt());
        }
    }
}
