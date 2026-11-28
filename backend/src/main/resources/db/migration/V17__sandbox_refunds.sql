CREATE TABLE payment_refunds (
    id UUID PRIMARY KEY,
    payment_id BIGINT NOT NULL UNIQUE REFERENCES payments(id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    currency VARCHAR(3) NOT NULL CHECK (currency = 'VND'),
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    provider_key UUID NOT NULL UNIQUE,
    charge_reference VARCHAR(255) NOT NULL,
    status VARCHAR(12) NOT NULL CHECK (status IN ('PENDING','PROCESSING','SUCCEEDED','FAILED')),
    refund_reference VARCHAR(255) UNIQUE,
    requested_by BIGINT NOT NULL REFERENCES users(id),
    attempts INTEGER NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    failure_code VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((status = 'SUCCEEDED') = (refund_reference IS NOT NULL))
);

CREATE INDEX idx_payment_refunds_queue ON payment_refunds (created_at)
    WHERE status IN ('PENDING','PROCESSING') AND failure_code IS DISTINCT FROM 'REVIEW_REQUIRED';

CREATE TABLE mock_refund_operations (
    provider_key UUID PRIMARY KEY,
    charge_reference VARCHAR(255) NOT NULL UNIQUE REFERENCES mock_payment_operations(transaction_id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    refund_reference VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE payment_refunds ENABLE ROW LEVEL SECURITY;
ALTER TABLE mock_refund_operations ENABLE ROW LEVEL SECURITY;
