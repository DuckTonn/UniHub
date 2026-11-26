package com.unihub.backend.service;

import com.unihub.backend.dto.PaymentGatewayResult;

import java.util.Optional;

public interface PaymentGatewayClient {
    String ATTEMPT_CLOSED = "payment_attempt_closed";

    PaymentGatewayResult charge(long amount, String key);

    /**
     * Reads a durable provider outcome. When closing, the provider must atomically
     * prevent a late charge for a key for which it has no prior outcome.
     */
    Optional<PaymentGatewayResult> reconcile(long amount, String key, boolean closeIfMissing);
}
