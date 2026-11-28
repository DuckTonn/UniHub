package com.unihub.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unihub.backend.exception.PaymentGatewayUnavailableException;
import com.unihub.backend.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Profile("!prod")
@ConditionalOnProperty(name = "app.payment-gateway.provider", havingValue = "STRIPE_SANDBOX")
public class StripeSandboxClient {
    private final ObjectMapper json;
    private final String apiKey;
    private final byte[] webhookSecret;
    private final URI apiOrigin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Autowired
    public StripeSandboxClient(ObjectMapper json, @Value("${app.payment-gateway.stripe.api-key}") String apiKey,
                               @Value("${app.payment-gateway.stripe.webhook-secret}") String webhookSecret) {
        this(json, apiKey, webhookSecret, URI.create("https://api.stripe.com"));
    }

    StripeSandboxClient(ObjectMapper json, String apiKey, String webhookSecret, URI apiOrigin) {
        if (!apiKey.matches("(sk|rk)_test_[A-Za-z0-9]+") || !webhookSecret.matches("whsec_[A-Za-z0-9]+")) {
            throw new IllegalStateException("Stripe sandbox requires a test API key and a webhook signing secret");
        }
        this.json = json;
        this.apiKey = apiKey;
        this.webhookSecret = webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.apiOrigin = apiOrigin;
    }

    Session create(UUID reference, long amount, String returnUrl) {
        return parseSession(send("/v1/checkout/sessions", Map.of(
                "mode", "payment", "payment_method_types[0]", "card", "client_reference_id", reference.toString(),
                "success_url", returnUrl + "?checkout=return", "cancel_url", returnUrl + "?checkout=cancelled",
                "line_items[0][quantity]", "1", "line_items[0][price_data][currency]", "vnd",
                "line_items[0][price_data][unit_amount]", Long.toString(amount),
                "line_items[0][price_data][product_data][name]", "UniHub workshop (sandbox)"
        ), "unihub-checkout-" + reference));
    }

    Session retrieve(String sessionId) {
        return parseSession(send(sessionPath(sessionId), null, null));
    }

    // Expiring can race with payment. Always retrieve authoritative state after a conflict.
    Session expire(String sessionId) {
        try {
            return parseSession(send(sessionPath(sessionId) + "/expire", Map.of(), null));
        } catch (PaymentGatewayUnavailableException exception) {
            return retrieve(sessionId);
        }
    }

    Event verifyEvent(byte[] body, String signature) {
        verifySignature(body, signature);
        JsonNode event;
        try {
            event = json.readTree(body);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid Stripe event");
        }
        if (event == null || !isTestMode(event) || !"event".equals(event.path("object").asText())) {
            throw new IllegalArgumentException("Expected a Stripe test event");
        }
        String kind = event.path("type").asText();
        if (!java.util.Set.of("checkout.session.completed", "checkout.session.expired",
                "checkout.session.async_payment_succeeded", "checkout.session.async_payment_failed").contains(kind)) {
            return null;
        }
        String eventId = event.path("id").asText();
        String sessionId = event.path("data").path("object").path("id").asText();
        if (!eventId.matches("evt_[A-Za-z0-9]{1,250}") || !validSessionId(sessionId)) {
            throw new IllegalArgumentException("Invalid Stripe event reference");
        }
        return new Event(eventId, sessionId);
    }

    private void verifySignature(byte[] body, String signature) {
        if (signature == null || signature.length() > 2048) {
            throw invalidSignature();
        }
        String timestamp = null;
        var signatures = new ArrayList<byte[]>();
        for (String entry : signature.split(",")) {
            if (entry.startsWith("t=")) {
                if (timestamp != null) throw invalidSignature();
                timestamp = entry.substring(2);
            } else if (entry.matches("v1=[0-9a-fA-F]{64}")) {
                signatures.add(HexFormat.of().parseHex(entry.substring(3)));
            }
        }
        long now = Instant.now().getEpochSecond();
        if (timestamp == null || !timestamp.matches("[0-9]{1,12}")) throw invalidSignature();
        long signedAt = Long.parseLong(timestamp);
        if (signedAt < now - 300 || signedAt > now + 300) throw invalidSignature();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret, "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            byte[] expected = mac.doFinal(body);
            if (signatures.stream().noneMatch(value -> MessageDigest.isEqual(expected, value))) throw invalidSignature();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot initialize Stripe signature verification", exception);
        }
    }

    private JsonNode send(String path, Map<String, String> form, String key) {
        var request = HttpRequest.newBuilder(apiOrigin.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + apiKey).header("Stripe-Version", "2024-06-20");
        if (form == null) {
            request.GET();
        } else {
            String encoded = form.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                    .collect(Collectors.joining("&"));
            request.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encoded));
        }
        if (key != null) request.header("Idempotency-Key", key);
        try {
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw unavailable();
            return json.readTree(response.body());
        } catch (IOException exception) {
            throw unavailable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private Session parseSession(JsonNode node) {
        if (node == null || !"checkout.session".equals(node.path("object").asText())
                || !isTestMode(node) || !validSessionId(node.path("id").asText())) {
            throw unavailable();
        }
        return new Session(node.path("id").asText(), node.path("client_reference_id").asText(),
                node.path("mode").asText(), node.path("amount_total").isIntegralNumber()
                        && node.path("amount_total").canConvertToLong() ? node.path("amount_total").asLong() : -1,
                node.path("currency").asText(), node.path("status").asText(), node.path("payment_status").asText(),
                node.path("payment_intent").asText(), node.path("url").asText());
    }

    private static boolean isTestMode(JsonNode node) {
        return node.path("livemode").isBoolean() && !node.path("livemode").asBoolean();
    }

    private String sessionPath(String sessionId) {
        if (!validSessionId(sessionId)) throw unavailable();
        return "/v1/checkout/sessions/" + sessionId;
    }

    private static boolean validSessionId(String id) {
        return id.matches("cs_test_[A-Za-z0-9]{1,247}");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static UnauthorizedException invalidSignature() {
        return new UnauthorizedException("Invalid Stripe webhook signature");
    }

    private static PaymentGatewayUnavailableException unavailable() {
        return new PaymentGatewayUnavailableException("Stripe sandbox result unavailable");
    }

    record Event(String id, String sessionId) {}
    record Session(String id, String reference, String mode, long amount, String currency, String status,
                   String paymentStatus, String paymentIntent, String url) {}
}
