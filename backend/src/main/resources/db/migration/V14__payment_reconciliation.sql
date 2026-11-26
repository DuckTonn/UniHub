ALTER TABLE payments ADD COLUMN failure_reason VARCHAR(255);

CREATE INDEX idx_payments_processing ON payments (id) WHERE status = 'PROCESSING';

-- Simulated provider ledger. Deliberately independent of the application payment row.
CREATE TABLE mock_payment_operations (
    idempotency_key VARCHAR(255) PRIMARY KEY,
    amount BIGINT NOT NULL CHECK (amount > 0),
    transaction_id VARCHAR(255) UNIQUE,
    CHECK (transaction_id IS NULL OR transaction_id <> '')
);
