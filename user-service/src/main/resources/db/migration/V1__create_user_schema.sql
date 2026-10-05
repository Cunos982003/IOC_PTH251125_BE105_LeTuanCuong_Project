-- V1: Create user service schema

-- users table
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('CUSTOMER', 'DRIVER')),
    full_name VARCHAR(255) NOT NULL,
    phone VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- drivers table (extends users, 1:1 via user_id)
CREATE TABLE IF NOT EXISTS drivers (
    user_id BIGINT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    license_no VARCHAR(100),
    status VARCHAR(20) NOT NULL CHECK (status IN ('OFFLINE', 'ONLINE')) DEFAULT 'OFFLINE'
);

-- vehicles table
CREATE TABLE IF NOT EXISTS vehicles (
    id BIGSERIAL PRIMARY KEY,
    driver_id BIGINT NOT NULL REFERENCES drivers(user_id) ON DELETE CASCADE,
    plate VARCHAR(50) NOT NULL UNIQUE,
    type VARCHAR(50) NOT NULL,
    model VARCHAR(100)
);

-- trip_history table
CREATE TABLE IF NOT EXISTS trip_history (
    trip_id UUID PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    driver_id BIGINT NOT NULL,
    fare BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL
);

-- outbox table
CREATE TABLE IF NOT EXISTS outbox (
    id BIGSERIAL PRIMARY KEY,
    stream VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_outbox_pending ON outbox (sent_at) WHERE sent_at IS NULL;