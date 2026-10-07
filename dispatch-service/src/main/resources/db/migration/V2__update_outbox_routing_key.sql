-- V2: Update outbox table for RabbitMQ
-- Change stream column to routing_key and update existing rows

-- Add new column
ALTER TABLE outbox ADD COLUMN IF NOT EXISTS routing_key VARCHAR(100);

-- Update routing_key based on existing stream values
UPDATE outbox
SET routing_key = CASE
    WHEN stream = 'events.trips' THEN 'trips.completed'
    ELSE 'trips.completed'
END
WHERE routing_key IS NULL;

-- Make routing_key NOT NULL
ALTER TABLE outbox ALTER COLUMN routing_key SET NOT NULL;

-- Drop old stream column
ALTER TABLE outbox DROP COLUMN IF EXISTS stream;

-- Update index
DROP INDEX IF EXISTS idx_outbox_pending;
CREATE INDEX idx_outbox_pending ON outbox (sent_at) WHERE sent_at IS NULL;