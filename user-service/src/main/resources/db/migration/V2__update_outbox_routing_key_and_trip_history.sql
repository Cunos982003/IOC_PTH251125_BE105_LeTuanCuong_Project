-- V2: Update outbox table for RabbitMQ and trip_history for TripCancelled

-- Rename stream column to routing_key
ALTER TABLE outbox RENAME COLUMN stream TO routing_key;

-- Update unsent events.users records to users.registered
UPDATE outbox
SET routing_key = 'users.registered'
WHERE sent_at IS NULL
  AND routing_key = 'events.users';

-- Make driver_id nullable in trip_history for TripCancelled events
ALTER TABLE trip_history ALTER COLUMN driver_id DROP NOT NULL;