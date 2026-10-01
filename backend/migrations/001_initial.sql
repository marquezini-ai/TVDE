CREATE TABLE licenses (
    activation_key TEXT PRIMARY KEY,
    role TEXT NOT NULL CHECK (role IN ('CLIENT', 'ADMIN')),
    expires_at INTEGER,
    revoked INTEGER NOT NULL DEFAULT 0 CHECK (revoked IN (0, 1)),
    created_at INTEGER NOT NULL
);

CREATE TABLE installations (
    installation_id TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    activation_key TEXT NOT NULL UNIQUE REFERENCES licenses(activation_key),
    public_jwk_json TEXT NOT NULL,
    key_thumbprint TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('CLIENT', 'ADMIN')),
    revoked INTEGER NOT NULL DEFAULT 0 CHECK (revoked IN (0, 1)),
    created_at INTEGER NOT NULL
);

CREATE TABLE nonces (
    principal TEXT NOT NULL,
    nonce TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    PRIMARY KEY (principal, nonce)
);
CREATE INDEX idx_nonces_expiry ON nonces(expires_at);

CREATE TABLE idempotency_records (
    endpoint TEXT NOT NULL,
    principal TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    body_hash TEXT NOT NULL,
    response_status INTEGER NOT NULL,
    response_json TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (endpoint, principal, idempotency_key)
);
CREATE INDEX idx_idempotency_expiry ON idempotency_records(expires_at);

CREATE TABLE events (
    owner_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    event_hash TEXT NOT NULL,
    event_json TEXT NOT NULL,
    recorded_at_epoch_ms INTEGER NOT NULL,
    platform TEXT NOT NULL CHECK (platform IN ('UBER', 'BOLT')),
    category TEXT,
    decision TEXT NOT NULL CHECK (decision IN ('ACEITAR', 'ANALISAR', 'REJEITAR')),
    trip_value_cents INTEGER NOT NULL,
    net_trip_value_cents INTEGER,
    value_per_km_cents INTEGER NOT NULL,
    gross_value_per_km_cents INTEGER NOT NULL,
    value_per_hour_cents INTEGER NOT NULL,
    pickup_municipality TEXT,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (owner_id, event_id)
);
CREATE INDEX idx_events_aggregate ON events(recorded_at_epoch_ms, platform, decision);

CREATE TABLE changes (
    owner_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    event_id TEXT NOT NULL,
    operation TEXT NOT NULL DEFAULT 'UPSERT' CHECK (operation = 'UPSERT'),
    created_at INTEGER NOT NULL,
    PRIMARY KEY (owner_id, sequence),
    UNIQUE (owner_id, event_id),
    FOREIGN KEY (owner_id, event_id) REFERENCES events(owner_id, event_id)
);

CREATE TABLE cursors (
    token TEXT PRIMARY KEY,
    owner_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX idx_cursors_expiry ON cursors(expires_at);

CREATE TABLE projection_outbox (
    outbox_id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id TEXT NOT NULL,
    event_id TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'PROCESSING', 'DONE')),
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at INTEGER NOT NULL,
    processing_started_at INTEGER,
    last_error_code TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    UNIQUE (owner_id, event_id),
    FOREIGN KEY (owner_id, event_id) REFERENCES events(owner_id, event_id)
);
CREATE INDEX idx_outbox_work ON projection_outbox(state, available_at, outbox_id);
