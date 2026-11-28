package com.unihub.backend.controller;

import com.unihub.backend.service.StripeCheckoutService;
import com.unihub.backend.exception.PaymentGatewayUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@Profile("!prod")
@ConditionalOnProperty(name = "app.payment-gateway.provider", havingValue = "STRIPE_SANDBOX")
@RequiredArgsConstructor
public class StripeWebhookController {
    public static final String PATH = "/api/v1/payment-webhooks/stripe";
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private final StripeCheckoutService checkout;

    @PostMapping(path = PATH, consumes = "application/json")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(HttpServletRequest request,
                        @RequestHeader(value = "Stripe-Signature", required = false) String signature) throws IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("Stripe webhook body exceeds 256 KiB");
        }
        try {
            checkout.webhook(body, signature);
        } catch (DataAccessException exception) {
            throw new PaymentGatewayUnavailableException("Stripe webhook result unavailable");
        }
    }
}
