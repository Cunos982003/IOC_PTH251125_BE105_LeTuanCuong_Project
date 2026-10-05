-- Wallets table
CREATE TABLE wallets (
    user_id BIGINT PRIMARY KEY,
    balance BIGINT NOT NULL CHECK (balance >= 0),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Ledger entries for all transactions
CREATE TABLE ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    trip_id UUID NOT NULL,
    user_id BIGINT NOT NULL,
    amount BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('FARE', 'PAYOUT', 'COMMISSION')),
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (trip_id, user_id, type)
);

CREATE INDEX idx_ledger_trip_id ON ledger_entries(trip_id);
CREATE INDEX idx_ledger_user_id ON ledger_entries(user_id);
CREATE INDEX idx_ledger_type ON ledger_entries(type);

-- Processed events for idempotency
CREATE TABLE processed_events (
    event_id UUID PRIMARY KEY,
    processed_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Payment failures
CREATE TABLE payment_failures (
    id BIGSERIAL PRIMARY KEY,
    trip_id UUID NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_failures_trip_id ON payment_failures(trip_id);

-- Create platform wallet with user_id = 0
INSERT INTO wallets (user_id, balance, updated_at) VALUES (0, 0, now());
