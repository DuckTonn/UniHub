package com.unihub.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

	@Bean(defaultCandidate = false)
	public ThreadPoolTaskScheduler aiDocumentScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setThreadNamePrefix("ai-document-");
		return scheduler;
	}

	@Bean(defaultCandidate = false)
	@ConditionalOnProperty(name = "app.payment-gateway.provider", havingValue = "STRIPE_SANDBOX")
	public ThreadPoolTaskScheduler stripeCheckoutScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setThreadNamePrefix("stripe-checkout-");
		return scheduler;
	}

	@Bean(name = "notificationTaskExecutor")
	public ThreadPoolTaskExecutor notificationTaskExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(4);
		executor.setMaxPoolSize(16);
		executor.setQueueCapacity(500);
		executor.setThreadNamePrefix("notification-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
		executor.initialize();
		return executor;
	}
}
