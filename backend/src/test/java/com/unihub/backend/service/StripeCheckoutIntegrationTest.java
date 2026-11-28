package com.unihub.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.unihub.backend.dto.RoomRequest;
import com.unihub.backend.dto.WorkshopRequest;
import com.unihub.backend.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {com.unihub.backend.BackendApplication.class, StripeCheckoutIntegrationTest.Fixture.class},
        properties = {"app.payment-gateway.provider=STRIPE_SANDBOX", "app.payment-gateway.stripe.checkout-enabled=true",
                "app.payment-gateway.stripe.api-key=sk_test_localFixture", "app.payment-gateway.stripe.webhook-secret=whsec_localFixture",
                "app.payment-gateway.stripe.initial-delay=PT1H", "app.scheduled-maintenance.enabled=false"})
class StripeCheckoutIntegrationTest {
    private static final String WEBHOOK = "/api/v1/payment-webhooks/stripe";
    private static final String SIGNING_SECRET = "whsec_localFixture";
    @LocalServerPort private int port;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private JwtUtil jwt;
    @Autowired private RoomService rooms;
    @Autowired private WorkshopService workshops;
    @Autowired private RegistrationService registrations;
    @Autowired private StripeCheckoutService checkout;
    @Autowired private StripeFixture stripe;

    @Test
    void replaysCheckoutAfterAmbiguousCreateAndConfirmsOnlyCanonicalSignedOutcome() throws Exception {
        Booking booking = register();
        stripe.loseNextCreate.set(true);
        assertThat(pay(booking, booking.student()).statusCode()).isEqualTo(503);
        var open = pay(booking, booking.student());
        assertThat(open.statusCode()).isEqualTo(202);
        assertThat(json.readTree(open.body()).path("checkoutStatus").asText()).isEqualTo("OPEN");
        assertThat(open.body()).contains("https://checkout.stripe.com/");
        assertThat(stripe.byKey).containsKey(booking.providerKey(jdbc));
        assertThat(pay(booking, booking.student() + 100_000).statusCode()).isEqualTo(404);
        assertThat(status(booking)).isEqualTo("PROCESSING");
        var session = stripe.byKey.get(booking.providerKey(jdbc));
        session.paid = true;
        String body = event("checkout.session.completed", session.id, false);
        assertThat(deliver(body, signature(body + " ", Instant.now()))).isEqualTo(401);
        assertThat(deliver(body, signature(body, Instant.now().minusSeconds(301)))).isEqualTo(401);
        String live = event("checkout.session.completed", session.id, true);
        assertThat(deliver(live, signature(live, Instant.now()))).isEqualTo(400);
        assertThat(status(booking)).isEqualTo("PROCESSING");
        assertThat(deliver(body, signature(body, Instant.now()))).isEqualTo(204);
        // An older event type still retrieves the currently paid session.
        String older = event("checkout.session.expired", session.id, false);
        assertThat(deliver(older, signature(older, Instant.now()))).isEqualTo(204);
        assertThat(deliver(body, signature(body, Instant.now()))).isEqualTo(204);
        assertThat(status(booking)).isEqualTo("COMPLETED");
        var completed = json.readTree(pay(booking, booking.student()).body());
        assertThat(completed.path("qrCode").asText()).startsWith("QR-");
        assertThat(completed.path("transactionId").asText()).isEqualTo("pi_" + session.id.substring(8));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stripe_checkout_events e JOIN stripe_checkout_attempts a ON a.id=e.checkout_id
                WHERE a.payment_id=?
                """, Long.class, booking.payment())).isEqualTo(2L);
    }

    @Test
    void workerSurfacesAmountDiscrepancyAndRecoversFromProviderOutage() throws Exception {
        Booking booking = register();
        assertThat(pay(booking, booking.student()).statusCode()).isEqualTo(202);
        var session = stripe.byKey.get(booking.providerKey(jdbc));
        session.paid = true;
        session.amount = 19_999;
        stripe.lookupUnavailable.set(true);
        dueOnly(booking);
        checkout.reconcile();
        assertThat(status(booking)).isEqualTo("PROCESSING");
        stripe.lookupUnavailable.set(false);
        dueOnly(booking);
        checkout.reconcile();
        assertThat(jdbc.queryForObject("SELECT issue FROM stripe_checkout_attempts WHERE payment_id=?", String.class,
                booking.payment())).isEqualTo("PROVIDER_MISMATCH");
        assertThat(status(booking)).isEqualTo("PROCESSING");
        session.amount = 20_000;
        dueOnly(booking);
        checkout.reconcile();
        assertThat(status(booking)).isEqualTo("COMPLETED");
    }

    @Test
    void expiredHoldExcludesLatePaymentFromAdmissionAndExpiresUnpaidCheckout() throws Exception {
        Booking late = register();
        stripe.loseNextCreate.set(true);
        assertThat(pay(late, late.student()).statusCode()).isEqualTo(503);
        age(late);
        dueOnly(late);
        checkout.reconcile();
        assertThat(ticketStatus(late)).isEqualTo("CANCELLED");
        var lateSession = stripe.byKey.get(late.providerKey(jdbc));
        lateSession.paid = true;
        String body = event("checkout.session.completed", lateSession.id, false);
        assertThat(deliver(body, signature(body, Instant.now()))).isEqualTo(204);
        assertThat(status(late)).isEqualTo("REFUND_REQUIRED");
        assertThat(ticketStatus(late)).isEqualTo("CANCELLED");

        Booking expired = register();
        assertThat(pay(expired, expired.student()).statusCode()).isEqualTo(202);
        assertThat(ticketStatus(expired)).isEqualTo("PENDING");
        age(expired);
        dueOnly(expired);
        checkout.reconcile();
        assertThat(stripe.byKey.get(expired.providerKey(jdbc)).expired).isTrue();
        assertThat(status(expired)).isEqualTo("CANCELLED");
        assertThat(ticketStatus(expired)).isEqualTo("CANCELLED");
    }

    private Booking register() {
        String name = "Stripe sandbox " + UUID.randomUUID();
        long room = rooms.createRoom(new RoomRequest(name, 2)).getId();
        var start = LocalDateTime.now().plusDays(3).withNano(0);
        long workshop = workshops.createWorkshop(new WorkshopRequest(name, null, room, null, 1, 20_000L,
                start, start.plusHours(1), start.minusDays(4), start.minusDays(1))).getId();
        workshops.publishWorkshop(workshop);
        long student = jdbc.queryForObject("SELECT min(id) FROM users WHERE role='STUDENT' AND status='ACTIVE'", Long.class);
        var key = registrations.register(workshop, student).getIdempotencyKey();
        return new Booking(workshop, student, key,
                jdbc.queryForObject("SELECT id FROM payments WHERE idempotency_key=?", Long.class, key));
    }

    private String status(Booking booking) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class, booking.payment());
    }

    private String ticketStatus(Booking booking) {
        return jdbc.queryForObject("""
                SELECT r.status FROM registrations r JOIN payments p ON r.id=p.registration_id WHERE p.id=?
                """, String.class, booking.payment());
    }

    private void dueOnly(Booking booking) {
        jdbc.update("UPDATE stripe_checkout_attempts SET next_check_at='infinity' WHERE payment_id<>?", booking.payment());
        jdbc.update("UPDATE stripe_checkout_attempts SET next_check_at=now() WHERE payment_id=?", booking.payment());
    }

    private void age(Booking booking) {
        jdbc.update("""
                UPDATE registrations SET created_at=created_at - interval '11 minutes'
                WHERE id=(SELECT registration_id FROM payments WHERE id=?)
                """, booking.payment());
    }

    private HttpResponse<String> pay(Booking booking, long user) throws IOException, InterruptedException {
        var request = request("/api/v1/workshops/" + booking.workshop() + "/pay")
                .header("Authorization", "Bearer " + jwt.generateAccessToken(user, "STUDENT"))
                .header("Idempotency-Key", booking.key()).POST(HttpRequest.BodyPublishers.noBody()).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private int deliver(String body, String signature) throws IOException, InterruptedException {
        var request = request(WEBHOOK).header("Content-Type", "application/json")
                .header("Stripe-Signature", signature).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private String event(String kind, String sessionId, boolean live) {
        return """
                {"object":"event","id":"evt_%s","livemode":%s,"type":"%s","data":{"object":{"id":"%s"}}}
                """.formatted(UUID.randomUUID().toString().replace("-", ""), live, kind, sessionId);
    }

    private String signature(String body, Instant time) throws Exception {
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        long timestamp = time.getEpochSecond();
        return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(
                mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private record Booking(long workshop, long student, String key, long payment) {
        String providerKey(JdbcTemplate jdbc) {
            return "unihub-checkout-" + jdbc.queryForObject(
                    "SELECT id FROM stripe_checkout_attempts WHERE payment_id=?", UUID.class, payment);
        }
    }

    @TestConfiguration
    static class Fixture {
        @Bean(destroyMethod = "close")
        StripeFixture stripeFixture(ObjectMapper json) throws IOException {
            return new StripeFixture(json);
        }

        @Bean
        @Primary
        StripeSandboxClient localStripeClient(ObjectMapper json, StripeFixture fixture) {
            return new StripeSandboxClient(json, "sk_test_localFixture", SIGNING_SECRET,
                    URI.create("http://127.0.0.1:" + fixture.server.getAddress().getPort()));
        }
    }

    static class StripeFixture implements AutoCloseable {
        private final HttpServer server;
        private final ObjectMapper json;
        private final Map<String, CheckoutSession> byKey = new ConcurrentHashMap<>();
        private final AtomicBoolean loseNextCreate = new AtomicBoolean();
        private final AtomicBoolean lookupUnavailable = new AtomicBoolean();

        StripeFixture(ObjectMapper json) throws IOException {
            this.json = json;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/checkout/sessions", this::respond);
            server.start();
        }

        private void respond(HttpExchange exchange) throws IOException {
            try (exchange) {
                var path = exchange.getRequestURI().getPath();
                var headers = exchange.getRequestHeaders();
                assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer sk_test_localFixture");
                CheckoutSession session;
                if (path.endsWith("/sessions")) {
                    var form = new HashMap<String, String>();
                    for (String entry : new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                        var parts = entry.split("=", 2);
                        form.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
                    }
                    assertThat(form).containsEntry("payment_method_types[0]", "card")
                            .containsEntry("line_items[0][price_data][currency]", "vnd")
                            .containsEntry("success_url", "http://localhost:5173/registration-history?checkout=return");
                    String reference = form.get("client_reference_id");
                    var key = headers.getFirst("Idempotency-Key");
                    assertThat(key).isEqualTo("unihub-checkout-" + reference);
                    session = byKey.computeIfAbsent(key, value -> new CheckoutSession(reference,
                            Long.parseLong(form.get("line_items[0][price_data][unit_amount]"))));
                    if (loseNextCreate.getAndSet(false)) {
                        exchange.sendResponseHeaders(503, -1);
                        return;
                    }
                } else {
                    if (lookupUnavailable.get()) {
                        exchange.sendResponseHeaders(503, -1);
                        return;
                    }
                    var lookup = path.split("/")[4];
                    session = byKey.values().stream().filter(s -> s.id.equals(lookup)).findFirst().orElseThrow();
                    if (path.endsWith("/expire")) session.expired = true;
                }
                JsonNode response = json.createObjectNode().put("id", session.id).put("object", "checkout.session")
                        .put("livemode", false).put("mode", "payment").put("client_reference_id", session.reference)
                        .put("currency", "vnd").put("amount_total", session.amount)
                        .put("payment_status", session.paid ? "paid" : "unpaid")
                        .put("status", session.paid ? "complete" : session.expired ? "expired" : "open")
                        .put("url", "https://checkout.stripe.com/c/pay/" + session.id)
                        .put("payment_intent", "pi_" + session.id.substring(8));
                byte[] body = json.writeValueAsBytes(response);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    static final class CheckoutSession {
        private final String id = "cs_test_" + UUID.randomUUID().toString().replace("-", "");
        private final String reference;
        private volatile long amount;
        private volatile boolean paid;
        private volatile boolean expired;

        CheckoutSession(String reference, long amount) {
            this.reference = reference;
            this.amount = amount;
        }
    }
}
