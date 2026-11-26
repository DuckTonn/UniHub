package com.unihub.backend.scheduler;

import com.unihub.backend.repository.RegistrationRepository;
import com.unihub.backend.repository.PaymentRepository;
import com.unihub.backend.service.PaymentService;
import com.unihub.backend.service.RegistrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class PendingRegistrationCancellationScheduler {

    private final RegistrationRepository registrationRepository;
    private final RegistrationService registrationService;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;

    @Scheduled(fixedDelay = 60_000)
    public void cancelStalePendingRegistrations() {
        var cutoff = LocalDateTime.now().minus(RegistrationService.HOLD_TTL);
        for (Long registrationId : registrationRepository.findExpiredPendingIds(cutoff)) {
            try {
                registrationService.expirePendingRegistration(registrationId, cutoff);
            } catch (RuntimeException exception) {
                log.warn("Failed to expire pending registration {}", registrationId, exception);
            }
        }
    }

    @Scheduled(fixedDelay = 30_000)
    public void reconcilePayments() {
        for (Long paymentId : paymentRepository.findProcessingIds()) {
            try {
                paymentService.reconcile(paymentId);
            } catch (RuntimeException exception) {
                log.warn("Could not reconcile payment {}", paymentId, exception);
            }
        }
    }
}
