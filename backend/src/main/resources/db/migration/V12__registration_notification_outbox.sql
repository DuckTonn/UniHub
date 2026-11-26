CREATE TABLE registration_notification_outbox (
    event_id UUID PRIMARY KEY,
    registration_id BIGINT NOT NULL UNIQUE REFERENCES registrations(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ
);

CREATE INDEX idx_registration_notification_outbox_pending
    ON registration_notification_outbox (created_at, event_id)
    WHERE published_at IS NULL;
