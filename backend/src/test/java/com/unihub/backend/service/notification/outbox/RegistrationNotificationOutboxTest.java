package com.unihub.backend.service.notification.outbox;

import com.unihub.backend.event.WorkshopRegistrationSuccessEvent;
import com.unihub.backend.service.RegistrationService;
import com.unihub.backend.service.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.unihub.backend.service.notification.outbox.RegistrationNotificationMessaging.DEAD_QUEUE;
import static com.unihub.backend.service.notification.outbox.RegistrationNotificationMessaging.EXCHANGE;
import static com.unihub.backend.service.notification.outbox.RegistrationNotificationMessaging.QUEUE;
import static com.unihub.backend.service.notification.outbox.RegistrationNotificationMessaging.ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@EnabledIfEnvironmentVariable(named = "RUN_NOTIFICATION_OUTBOX_IT", matches = "true")
@SpringBootTest(properties = {"notification.outbox.enabled=true", "notification.outbox.initial-delay=1h",
        "notification.email.enabled=false", "notification.telegram.enabled=false"})
class RegistrationNotificationOutboxTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate transaction;
    @Autowired private RegistrationService registration;
    @Autowired private RegistrationNotificationRelay relay;
    @Autowired private RegistrationNotificationConsumer consumer;
    @Autowired private NotificationService notifications;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private AmqpAdmin broker;

    private Long userId;
    private Long workshopId;
    private String workshopTitle;

    @BeforeEach
    void createWorkshop() {
        userId = jdbc.queryForObject("""
                SELECT id FROM users WHERE role = 'STUDENT' AND status = 'ACTIVE' ORDER BY id LIMIT 1
                """, Long.class);
        workshopTitle = "Outbox fixture " + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        workshopId = jdbc.queryForObject("""
                INSERT INTO workshops (title, total_slots, remaining_slots, price, status, room_id,
                    registration_start_time, registration_end_time, start_time, end_time)
                VALUES (?, 10, 10, 0, 'PUBLISHED', (SELECT min(id) FROM rooms), ?, ?, ?, ?)
                RETURNING id
                """, Long.class, workshopTitle, now.minusHours(1), now.plusHours(1),
                now.plusDays(1), now.plusDays(1).plusHours(2));
    }

    @Test
    void rollsBackOutboxWithTheRegistrationTransaction() {
        transaction.executeWithoutResult(status -> {
            long registrationId = jdbc.queryForObject("""
                    INSERT INTO registrations (user_id, workshop_id, qr_code, status)
                    VALUES (?, ?, ?, 'SUCCESS') RETURNING id
                    """, Long.class, userId, workshopId, UUID.randomUUID().toString());
            var event = new WorkshopRegistrationSuccessEvent(registrationId);
            events.publishEvent(event);
            events.publishEvent(event);
            assertThat(outboxCount()).isEqualTo(1);
            status.setRollbackOnly();
        });

        assertThat(outboxCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM registrations WHERE workshop_id = ?",
                Integer.class, workshopId)).isZero();
    }

    @Test
    void retriesUnroutablePublicationAndPersistsOnlyOneInAppNotification() {
        var binding = new Binding(QUEUE, Binding.DestinationType.QUEUE, EXCHANGE, ROUTING_KEY, null);
        // Force declarations before temporarily removing the route.
        broker.getQueueInfo(QUEUE);
        broker.removeBinding(binding);
        UUID eventId;
        try {
            registration.register(workshopId, userId);
            assertThat(outboxCount()).isEqualTo(1);
            eventId = jdbc.queryForObject("""
                    SELECT o.event_id FROM registration_notification_outbox o
                    JOIN registrations r ON r.id = o.registration_id WHERE r.workshop_id = ?
                    """, UUID.class, workshopId);
            relay.relayPending();
            assertThat(outboxTimestampIsSet(eventId, "published_at")).isFalse();
        } finally {
            broker.declareBinding(binding);
        }

        relay.relayPending();
        await().atMost(Duration.ofSeconds(15)).until(() -> outboxTimestampIsSet(eventId, "delivered_at"));
        assertThat(outboxTimestampIsSet(eventId, "published_at")).isTrue();

        consumer.deliver(eventId.toString());
        assertThat(notifications.getUserNotifications(userId, 0, 100).getContent())
                .filteredOn(notification -> notification.contentHtml().contains(workshopTitle))
                .singleElement().satisfies(notification -> {
                    assertThat(notification.channel()).isEqualTo("IN_APP");
                    assertThat(notification.status()).isEqualTo("SUCCESS");
                });
    }

    @Test
    void deadLettersAnUnprocessableMessageAfterRetries() {
        String missingEvent = UUID.randomUUID().toString();
        rabbit.convertAndSend(EXCHANGE, ROUTING_KEY, missingEvent);
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(rabbit.receiveAndConvert(DEAD_QUEUE)).isEqualTo(missingEvent));
    }

    private int outboxCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM registration_notification_outbox o
                JOIN registrations r ON r.id = o.registration_id WHERE r.workshop_id = ?
                """, Integer.class, workshopId);
    }

    private boolean outboxTimestampIsSet(UUID eventId, String column) {
        return jdbc.queryForObject("SELECT " + column + " IS NOT NULL FROM registration_notification_outbox"
                + " WHERE event_id = ?", Boolean.class, eventId);
    }
}
