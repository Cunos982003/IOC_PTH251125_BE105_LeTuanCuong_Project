-- V2: Create outbox table for event publishing
CREATE TABLE IF NOT EXISTS outbox (
    id BIGSERIAL PRIMARY KEY,
    stream VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ
);

-- Index for pending messages (sent_at IS NULL)
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox (sent_at) WHERE sent_at IS NULL;