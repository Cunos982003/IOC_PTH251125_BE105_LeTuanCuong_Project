-- V1: Create dispatch tables

CREATE TABLE IF NOT EXISTS trips (
    id UUID PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    driver_id BIGINT,
    status TEXT NOT NULL,
    pickup_lat DOUBLE PRECISION NOT NULL,
    pickup_lng DOUBLE PRECISION NOT NULL,
    dropoff_lat DOUBLE PRECISION NOT NULL,
    dropoff_lng DOUBLE PRECISION NOT NULL,
    distance_m BIGINT NOT NULL,
    fare BIGINT NOT NULL,
    surge NUMERIC(3,2),
    idempotency_key TEXT,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_customer_idempotency UNIQUE (customer_id, idempotency_key)
);

-- Unique index: only one active trip per driver
CREATE UNIQUE INDEX uq_driver_active_trip ON trips(driver_id)
    WHERE status IN ('ACCEPTED', 'PICKING_UP', 'IN_TRIP');

-- Index for customer queries
CREATE INDEX idx_trips_customer ON trips(customer_id, created_at DESC);

-- Trip state transition history
CREATE TABLE IF NOT EXISTS trip_events (
    id BIGSERIAL PRIMARY KEY,
    trip_id UUID NOT NULL,
    from_status TEXT,
    to_status TEXT NOT NULL,
    at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_trip_events_trip ON trip_events(trip_id, at);

-- Driver offers for trips
CREATE TABLE IF NOT EXISTS offers (
    trip_id UUID NOT NULL,
    driver_id BIGINT NOT NULL,
    status TEXT NOT NULL,
    offered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    accepted_at TIMESTAMPTZ,
    expired_at TIMESTAMPTZ,
    PRIMARY KEY (trip_id, driver_id)
);

CREATE INDEX idx_offers_driver ON offers(driver_id, offered_at DESC);

-- Outbox for event publishing
CREATE TABLE IF NOT EXISTS outbox (
    id BIGSERIAL PRIMARY KEY,
    stream VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_pending ON outbox (sent_at) WHERE sent_at IS NULL;
