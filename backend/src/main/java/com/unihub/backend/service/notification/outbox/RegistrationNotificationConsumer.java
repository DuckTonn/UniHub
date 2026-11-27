package com.unihub.backend.service.notification.outbox;

import com.unihub.backend.service.notification.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@Slf4j
@ConditionalOnProperty(name = "notification.outbox.enabled", havingValue = "true")
class RegistrationNotificationConsumer {

    private final RegistrationNotificationOutbox outbox;
    private final NotificationService notifications;

    RegistrationNotificationConsumer(RegistrationNotificationOutbox outbox, NotificationService notifications) {
        this.outbox = outbox;
        this.notifications = notifications;
    }

    @RabbitListener(queues = RegistrationNotificationMessaging.QUEUE)
    @Transactional
    public void deliver(String messageId) {
        UUID eventId = UUID.fromString(messageId);
        Long registrationId = outbox.lockUndeliveredRegistration(eventId);
        if (registrationId != null) {
            notifications.saveRegistrationSuccessInApp(registrationId);
            outbox.markDelivered(eventId);
            log.info("Handled registration notification event {}", eventId);
        }
    }
}
