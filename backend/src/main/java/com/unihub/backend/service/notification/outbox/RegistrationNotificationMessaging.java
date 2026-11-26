package com.unihub.backend.service.notification.outbox;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "notification.outbox.enabled", havingValue = "true")
class RegistrationNotificationMessaging {

    static final String EXCHANGE = "unihub.notifications";
    static final String ROUTING_KEY = "registration.succeeded.in-app.v1";
    static final String QUEUE = "unihub.notifications.registration.in-app.v1";
    static final String DEAD_QUEUE = "unihub.notifications.registration.in-app.v1.dead";

    @Bean
    Declarables registrationNotificationTopology() {
        var exchange = new DirectExchange(EXCHANGE);
        var queue = QueueBuilder.durable(QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(DEAD_QUEUE)
                .build();
        return new Declarables(exchange, queue, QueueBuilder.durable(DEAD_QUEUE).build(),
                BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY));
    }
}
