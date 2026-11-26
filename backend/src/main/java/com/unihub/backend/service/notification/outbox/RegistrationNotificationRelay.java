package com.unihub.backend.service.notification.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.unihub.backend.service.notification.outbox.RegistrationNotificationMessaging.EXCHANGE;
import static com.unihub.backend.service.notification.outbox.RegistrationNotificationMessaging.ROUTING_KEY;

@Slf4j
@Component
@ConditionalOnProperty(name = "notification.outbox.enabled", havingValue = "true")
class RegistrationNotificationRelay {

    private final RegistrationNotificationOutbox outbox;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transaction;

    RegistrationNotificationRelay(RegistrationNotificationOutbox outbox, RabbitTemplate rabbit,
                                  TransactionTemplate transaction) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.transaction = transaction;
    }

    @Scheduled(fixedDelayString = "${notification.outbox.poll-interval:2s}",
            initialDelayString = "${notification.outbox.initial-delay:2s}")
    public void relayPending() {
        try {
            for (int count = 0; count < 20; count++) {
                if (!Boolean.TRUE.equals(transaction.execute(status -> publishNext()))) {
                    break;
                }
            }
        } catch (AmqpException exception) {
            log.warn("Registration notification outbox publish will retry ({})", exception.getClass().getSimpleName());
        }
    }

    private boolean publishNext() {
        var eventId = outbox.lockNextUnpublished();
        if (eventId.isEmpty()) {
            return false;
        }

        publish(eventId.get());
        outbox.markPublished(eventId.get());
        return true;
    }

    private void publish(UUID eventId) {
        String messageId = eventId.toString();
        var correlation = new CorrelationData(messageId);
        rabbit.convertAndSend(EXCHANGE, ROUTING_KEY, messageId, message -> {
            message.getMessageProperties().setMessageId(messageId);
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        }, correlation);
        try {
            var confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.ack() || correlation.getReturned() != null) {
                throw new AmqpException("Broker did not accept the registration notification");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Interrupted waiting for broker confirmation", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new AmqpException("No broker confirmation for registration notification", exception);
        }
    }
}
