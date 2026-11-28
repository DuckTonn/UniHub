package com.unihub.backend.service;

import com.unihub.backend.dto.PaymentGatewayResult;
import com.unihub.backend.dto.PaymentResultResponse;
import com.unihub.backend.exception.ConflictException;
import com.unihub.backend.exception.PaymentFailedException;
import com.unihub.backend.exception.PaymentGatewayUnavailableException;
import com.unihub.backend.service.StripeSandboxClient.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@Profile("!prod")
@ConditionalOnProperty(name = "app.payment-gateway.provider", havingValue = "STRIPE_SANDBOX")
public class StripeCheckoutService {
    private static final Logger log = LoggerFactory.getLogger(StripeCheckoutService.class);
    private static final String CHECKOUT_QUERY = """
            SELECT a.id, a.payment_id, a.return_url, a.session_id, p.amount, p.status,
                   r.status AS registration_status, r.created_at
            FROM stripe_checkout_attempts a JOIN payments p ON p.id = a.payment_id
            JOIN registrations r ON r.id = p.registration_id
            """;

    private final StripeSandboxClient stripe;
    private final RegistrationService registrations;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final boolean accepting;
    private final String returnUrl;

    public StripeCheckoutService(StripeSandboxClient stripe, RegistrationService registrations, JdbcClient jdbc,
                                 TransactionTemplate transaction,
                                 @Value("${app.payment-gateway.stripe.checkout-enabled}") boolean accepting,
                                 @Value("${spring.application.frontend-url}") String frontendUrl) {
        this.stripe = stripe;
        this.registrations = registrations;
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.accepting = accepting;
        URI origin = URI.create(frontendUrl);
        boolean loopback = "localhost".equals(origin.getHost()) || "127.0.0.1".equals(origin.getHost());
        if (origin.getHost() == null || origin.getUserInfo() != null
                || !("https".equals(origin.getScheme()) || (loopback && "http".equals(origin.getScheme())))
                || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))
                || origin.getQuery() != null || origin.getFragment() != null) {
            throw new IllegalStateException("Stripe sandbox requires a frontend HTTPS origin or HTTP loopback");
        }
        this.returnUrl = origin.resolve("/registration-history").toString();
    }

    public PaymentResultResponse checkout(long workshopId, long userId, String key) {
        var start = Objects.requireNonNull(transaction.execute(status -> {
            var payment = registrations.beginPayment(workshopId, userId, key, accepting, "STRIPE_SANDBOX");
            if (payment.settled() != null) return new Start(payment.settled(), null);
            var existing = find("a.payment_id = ?", payment.id(), false);
            if (existing.isEmpty()) {
                jdbc.sql("INSERT INTO stripe_checkout_attempts (id, payment_id, return_url) VALUES (?, ?, ?)")
                        .params(UUID.randomUUID(), payment.id(), returnUrl).update();
                existing = find("a.payment_id = ?", payment.id(), false);
            }
            return new Start(null, existing.orElseThrow());
        }));
        if (start.settled() != null) {
            if (!start.settled().isSuccess()) throw new PaymentFailedException("Payment failed: " + start.settled().getMessage());
            return start.settled();
        }

        var checkout = start.checkout();
        if (checkout.closed()) throw new ConflictException("Registration no longer has an active seat hold");
        Session session = checkout.sessionId() == null
                ? stripe.create(checkout.id(), checkout.amount(), checkout.returnUrl()) : stripe.retrieve(checkout.sessionId());
        return apply(checkout.id(), session, null, true);
    }

    public void webhook(byte[] body, String signature) {
        var event = stripe.verifyEvent(body, signature);
        if (event == null || Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM stripe_checkout_events WHERE event_id = ?)
                """).param(event.id()).query(Boolean.class).single())) {
            return;
        }
        Session session = stripe.retrieve(event.sessionId());
        if (!event.sessionId().equals(session.id())) throw unavailable();
        // Stripe accounts can serve several applications. A canonical reference identifies our own attempt,
        // including sessions created immediately before a connection failure or application restart.
        var checkout = find("a.session_id = ?", event.sessionId(), false);
        if (checkout.isEmpty()) {
            try {
                checkout = find("a.id = ?", UUID.fromString(session.reference()), false);
            } catch (IllegalArgumentException unknownReference) {
                return;
            }
        }
        checkout.ifPresent(row -> apply(row.id(), session, event.id(), false));
    }

    private PaymentResultResponse apply(UUID id, Session session, String eventId, boolean redirect) {
        return transaction.execute(status -> {
            var checkout = find("a.id = ?", id, true).orElseThrow();
            if (eventId != null && jdbc.sql("""
                    INSERT INTO stripe_checkout_events (event_id, checkout_id) VALUES (?, ?) ON CONFLICT DO NOTHING
                    """).params(eventId, id).update() == 0) {
                return null;
            }
            if (!"PROCESSING".equals(checkout.status())) {
                return PaymentResultResponse.checkout("VERIFYING", null);
            }
            boolean matchingSession = checkout.id().toString().equals(session.reference())
                    && (checkout.sessionId() == null || checkout.sessionId().equals(session.id()));
            if (matchingSession && checkout.sessionId() == null) {
                jdbc.sql("UPDATE stripe_checkout_attempts SET session_id = ?, next_check_at = now() WHERE id = ?")
                        .params(session.id(), id).update();
            }
            boolean paid = "paid".equals(session.paymentStatus());
            if (!matchingSession || !"payment".equals(session.mode()) || checkout.amount() != session.amount()
                    || !"vnd".equals(session.currency()) || (paid && (!"complete".equals(session.status())
                        || !session.paymentIntent().matches("pi_[A-Za-z0-9]{1,250}")))) {
                return review(checkout, "PROVIDER_MISMATCH");
            }
            if ("complete".equals(session.status()) && !paid) {
                return review(checkout, "PAYMENT_UNCONFIRMED");
            }
            jdbc.sql("UPDATE stripe_checkout_attempts SET issue = NULL WHERE id = ? AND issue IS NOT NULL")
                    .param(id).update();
            if (paid) {
                var result = registrations.settlePayment(checkout.paymentId(), PaymentGatewayResult.builder()
                        .success(true).transactionId(session.paymentIntent()).build(), true);
                return result.isSuccess() ? result : PaymentResultResponse.checkout("REVIEW_REQUIRED", null);
            }
            if ("expired".equals(session.status()) && "unpaid".equals(session.paymentStatus())) {
                registrations.settlePayment(checkout.paymentId(), PaymentGatewayResult.builder()
                        .failureReason(PaymentGatewayClient.ATTEMPT_CLOSED).build());
                return PaymentResultResponse.checkout("EXPIRED", null);
            }
            String checkoutUrl = redirect && !checkout.closed() && "open".equals(session.status())
                    && "unpaid".equals(session.paymentStatus()) ? validatedUrl(session.url()) : null;
            return PaymentResultResponse.checkout(checkoutUrl == null ? "VERIFYING" : "OPEN", checkoutUrl);
        });
    }

    private PaymentResultResponse review(Checkout checkout, String issue) {
        if (jdbc.sql("UPDATE stripe_checkout_attempts SET issue = ? WHERE id = ? AND issue IS DISTINCT FROM ?")
                .params(issue, checkout.id(), issue).update() == 1) {
            log.warn("Stripe sandbox requires review: paymentId={} checkoutId={} issue={}",
                    checkout.paymentId(), checkout.id(), issue);
        }
        return PaymentResultResponse.checkout("REVIEW_REQUIRED", null);
    }

    @Scheduled(fixedDelayString = "${app.payment-gateway.stripe.poll-interval:PT2S}",
            initialDelayString = "${app.payment-gateway.stripe.initial-delay:PT5S}", scheduler = "stripeCheckoutScheduler")
    void reconcile() {
        // Claim at most one durable job per tick. The lease leaves time for bounded Stripe requests.
        var claimed = jdbc.sql("""
                WITH due AS (
                    SELECT a.id FROM stripe_checkout_attempts a JOIN payments p ON p.id = a.payment_id
                    WHERE p.status = 'PROCESSING' AND a.next_check_at <= now()
                    ORDER BY a.next_check_at, a.id LIMIT 1 FOR UPDATE OF a SKIP LOCKED
                )
                UPDATE stripe_checkout_attempts a SET next_check_at = now() + interval '30 seconds'
                FROM due WHERE a.id = due.id RETURNING a.id
                """).query(UUID.class).optional();
        claimed.flatMap(id -> find("a.id = ?", id, false)).ifPresent(checkout -> {
            try {
                if (checkout.closed()) registrations.releaseExpiredStripeHold(checkout.paymentId());
                if (checkout.sessionId() == null) {
                    if (checkout.closed()) {
                        jdbc.sql("""
                                UPDATE stripe_checkout_attempts SET issue = COALESCE(issue, 'SESSION_UNKNOWN'),
                                    next_check_at = 'infinity'
                                WHERE id = ? AND session_id IS NULL
                                """).param(checkout.id()).update();
                    }
                    return;
                }
                var session = stripe.retrieve(checkout.sessionId());
                if (checkout.closed() && "open".equals(session.status())) {
                    session = stripe.expire(checkout.sessionId());
                }
                apply(checkout.id(), session, null, false);
            } catch (PaymentGatewayUnavailableException exception) {
                log.warn("Stripe sandbox lookup unavailable: paymentId={}", checkout.paymentId());
            }
        });
    }

    private Optional<Checkout> find(String condition, Object parameter, boolean lock) {
        // Conditions are constant SQL fragments supplied solely by this service.
        return jdbc.sql(CHECKOUT_QUERY + " WHERE " + condition + (lock ? " FOR UPDATE OF a" : ""))
                .param(parameter).query((row, index) -> new Checkout(row.getObject("id", UUID.class),
                        row.getLong("payment_id"), row.getString("return_url"), row.getString("session_id"),
                        row.getLong("amount"), row.getString("status"),
                        !"PENDING".equals(row.getString("registration_status"))
                                || !row.getObject("created_at", LocalDateTime.class)
                                .plus(RegistrationService.HOLD_TTL).isAfter(LocalDateTime.now()))).optional();
    }

    private String validatedUrl(String url) {
        try {
            URI target = URI.create(url);
            if ("https".equals(target.getScheme()) && "checkout.stripe.com".equals(target.getHost())
                    && target.getUserInfo() == null && target.getPort() == -1) {
                return url;
            }
        } catch (IllegalArgumentException invalidUri) {
            throw unavailable();
        }
        throw unavailable();
    }

    private PaymentGatewayUnavailableException unavailable() {
        return new PaymentGatewayUnavailableException("Stripe sandbox result unavailable");
    }

    private record Start(PaymentResultResponse settled, Checkout checkout) {}
    private record Checkout(UUID id, long paymentId, String returnUrl, String sessionId, long amount, String status,
                            boolean closed) {}
}
