-- V1: Create location_history table
CREATE TABLE IF NOT EXISTS location_history (
    id BIGSERIAL PRIMARY KEY,
    driver_id BIGINT NOT NULL,
    trip_id UUID,
    lat DOUBLE PRECISION NOT NULL,
    lng DOUBLE PRECISION NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_location_history_trip_recorded
    ON location_history (trip_id, recorded_at);