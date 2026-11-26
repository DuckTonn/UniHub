package com.unihub.backend.service;

import com.unihub.backend.dto.PaymentGatewayResult;
import com.unihub.backend.dto.PaymentResultResponse;
import com.unihub.backend.exception.ConflictException;
import com.unihub.backend.exception.PaymentFailedException;
import com.unihub.backend.exception.PaymentGatewayUnavailableException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final RegistrationService registrations;
    private final Optional<PaymentGatewayClient> provider;
    private final PaymentGatewayCircuitBreaker circuitBreaker;

    public PaymentResultResponse pay(Long workshopId, Long userId, String key) {
        var attempt = registrations.beginPayment(workshopId, userId, key, provider.isPresent());
        if (attempt.settled() != null) {
            return toApiResult(attempt.settled());
        }
        var result = attempt.closeIfMissing()
                ? readProviderOutcome(attempt).orElseThrow(this::unavailable)
                : charge(attempt);
        return toApiResult(settle(attempt.id(), result));
    }

    public void reconcile(Long paymentId) {
        if (provider.isEmpty()) {
            return;
        }
        registrations.reconciliationAttempt(paymentId).ifPresent(attempt ->
                readProviderOutcome(attempt)
                        .ifPresent(result -> settle(paymentId, result)));
    }

    private Optional<PaymentGatewayResult> readProviderOutcome(RegistrationService.PaymentAttempt attempt) {
        try {
            return provider.orElseThrow(this::unavailable)
                    .reconcile(attempt.amount(), attempt.key(), attempt.closeIfMissing());
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    private PaymentGatewayResult charge(RegistrationService.PaymentAttempt attempt) {
        if (!circuitBreaker.tryAcquirePermission()) {
            throw unavailable();
        }
        try {
            var result = provider.orElseThrow(this::unavailable).charge(attempt.amount(), attempt.key());
            requireDefinitiveResult(result);
            circuitBreaker.recordSuccess();
            return result;
        } catch (DataAccessException | PaymentGatewayUnavailableException exception) {
            circuitBreaker.recordFailure();
            throw unavailable();
        } catch (RuntimeException exception) {
            circuitBreaker.recordFailure();
            throw exception;
        }
    }

    private PaymentResultResponse settle(Long paymentId, PaymentGatewayResult result) {
        requireDefinitiveResult(result);
        return registrations.settlePayment(paymentId, result);
    }

    private void requireDefinitiveResult(PaymentGatewayResult result) {
        var reason = result.getFailureReason();
        if ((result.isSuccess() && (result.getTransactionId() == null || result.getTransactionId().isBlank()))
                || (!result.isSuccess() && (reason == null || reason.contains("payment_gateway_unavailable")
                || reason.startsWith("gateway_http_5")))) {
            throw unavailable();
        }
    }

    private PaymentGatewayUnavailableException unavailable() {
        return new PaymentGatewayUnavailableException("Payment gateway unavailable");
    }

    private PaymentResultResponse toApiResult(PaymentResultResponse response) {
        if (response.isSuccess()) {
            return response;
        }
        if (PaymentGatewayClient.ATTEMPT_CLOSED.equals(response.getMessage())) {
            throw new ConflictException("Registration no longer has an active seat hold");
        }
        String reason = response.getMessage() == null ? "payment_failed" : response.getMessage();
        throw new PaymentFailedException("Payment failed: " + reason);
    }
}
