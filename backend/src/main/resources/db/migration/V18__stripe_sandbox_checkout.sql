ALTER TABLE payments ADD COLUMN provider VARCHAR(20) NOT NULL DEFAULT 'MOCK'
    CHECK (provider IN ('MOCK', 'STRIPE_SANDBOX'));

CREATE TABLE stripe_checkout_attempts (
    id UUID PRIMARY KEY,
    payment_id BIGINT NOT NULL UNIQUE REFERENCES payments(id),
    session_id VARCHAR(255) UNIQUE,
    return_url VARCHAR(2048) NOT NULL,
    issue VARCHAR(40),
    next_check_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_stripe_checkout_due ON stripe_checkout_attempts (next_check_at);

CREATE TABLE stripe_checkout_events (
    event_id VARCHAR(255) PRIMARY KEY,
    checkout_id UUID NOT NULL REFERENCES stripe_checkout_attempts(id),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE stripe_checkout_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE stripe_checkout_events ENABLE ROW LEVEL SECURITY;
