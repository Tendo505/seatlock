-- Seat inventory lives on the event row. Every change to `available` is a
-- single conditional UPDATE, and the CHECK constraint is a second line of
-- defence: even a buggy code path cannot drive availability below zero.
CREATE TABLE events (
    id          UUID PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    venue       VARCHAR(200) NOT NULL,
    starts_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    capacity    INT NOT NULL,
    available   INT NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT events_capacity_positive CHECK (capacity > 0),
    CONSTRAINT events_available_in_range CHECK (available >= 0 AND available <= capacity)
);

-- A hold reserves seats for a short time. It is either confirmed into a
-- booking or released (by the customer, or by the expiry job).
CREATE TABLE holds (
    id              UUID PRIMARY KEY,
    event_id        UUID NOT NULL REFERENCES events (id),
    quantity        INT NOT NULL,
    customer_email  VARCHAR(320) NOT NULL,
    status          VARCHAR(16) NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT holds_quantity_positive CHECK (quantity > 0),
    CONSTRAINT holds_status_valid CHECK (status IN ('HELD', 'CONFIRMED', 'RELEASED', 'EXPIRED', 'CANCELLED'))
);

CREATE INDEX holds_status_expires_idx ON holds (status, expires_at);
CREATE INDEX holds_event_idx ON holds (event_id);

-- Idempotency-Key -> the hold it created. The primary key makes concurrent
-- retries with the same key serialise on insert, so only one can win.
CREATE TABLE idempotency_keys (
    idem_key      VARCHAR(100) PRIMARY KEY,
    request_hash  VARCHAR(64) NOT NULL,
    hold_id       UUID REFERENCES holds (id),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL
);
