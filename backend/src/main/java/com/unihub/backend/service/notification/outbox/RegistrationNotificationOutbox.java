package com.unihub.backend.service.notification.outbox;

import com.unihub.backend.event.WorkshopRegistrationSuccessEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
@ConditionalOnProperty(name = "notification.outbox.enabled", havingValue = "true")
class RegistrationNotificationOutbox {

    private final JdbcTemplate jdbc;

    RegistrationNotificationOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(WorkshopRegistrationSuccessEvent event) {
        UUID eventId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO registration_notification_outbox (event_id, registration_id)
                VALUES (?, ?)
                ON CONFLICT (registration_id) DO NOTHING
                """, eventId, event.registrationId());
        if (inserted > 0) log.info("Queued registration notification event {}", eventId);
    }

    Optional<UUID> lockNextUnpublished() {
        return jdbc.query("""
                SELECT event_id FROM registration_notification_outbox
                WHERE published_at IS NULL
                ORDER BY created_at, event_id
                LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (row, index) -> row.getObject("event_id", UUID.class)).stream().findFirst();
    }

    void markPublished(UUID eventId) {
        jdbc.update("""
                UPDATE registration_notification_outbox SET published_at = CURRENT_TIMESTAMP
                WHERE event_id = ?
                """, eventId);
    }

    Long lockUndeliveredRegistration(UUID eventId) {
        return jdbc.queryForObject("""
                SELECT CASE WHEN delivered_at IS NULL THEN registration_id END
                FROM registration_notification_outbox WHERE event_id = ? FOR UPDATE
                """, Long.class, eventId);
    }

    void markDelivered(UUID eventId) {
        jdbc.update("""
                UPDATE registration_notification_outbox SET delivered_at = CURRENT_TIMESTAMP
                WHERE event_id = ?
                """, eventId);
    }
}
