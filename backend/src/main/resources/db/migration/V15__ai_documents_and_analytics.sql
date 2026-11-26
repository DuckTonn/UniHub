CREATE SEQUENCE workshop_ai_generation;

CREATE TABLE workshop_ai_documents (
    workshop_id BIGINT PRIMARY KEY REFERENCES workshops(id) ON DELETE CASCADE,
    generation BIGINT NOT NULL DEFAULT nextval('workshop_ai_generation'),
    status VARCHAR(12) NOT NULL CHECK (status IN ('QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED')),
    document BYTEA CHECK (octet_length(document) BETWEEN 1 AND 10485760),
    original_description TEXT,
    original_speaker VARCHAR(255),
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    error VARCHAR(255)
);

CREATE INDEX idx_workshop_ai_available ON workshop_ai_documents (available_at, generation)
    WHERE status IN ('QUEUED', 'PROCESSING');

CREATE TABLE analytics_reports (
    name VARCHAR(50) PRIMARY KEY,
    captured_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL
);
