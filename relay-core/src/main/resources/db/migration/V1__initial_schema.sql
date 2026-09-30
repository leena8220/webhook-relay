CREATE TABLE webhook_endpoint (
    id UUID PRIMARY KEY,
    target_url TEXT NOT NULL,
    description VARCHAR(500),
    secret_ciphertext BYTEA NOT NULL,
    secret_nonce BYTEA NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE webhook_event (
    id UUID PRIMARY KEY,
    endpoint_id UUID NOT NULL REFERENCES webhook_endpoint(id),
    idempotency_key VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    correlation_id VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_event_endpoint_idempotency UNIQUE (endpoint_id, idempotency_key)
);

CREATE TABLE webhook_delivery (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE REFERENCES webhook_event(id),
    endpoint_id UUID NOT NULL REFERENCES webhook_endpoint(id),
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    dispatch_sequence INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    processing_token UUID,
    processing_until TIMESTAMPTZ,
    response_status INTEGER,
    response_body TEXT,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    dead_lettered_at TIMESTAMPTZ,
    CONSTRAINT ck_delivery_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRYING', 'DELIVERED', 'DEAD_LETTERED')
    ),
    CONSTRAINT ck_delivery_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_delivery_dispatch_sequence CHECK (dispatch_sequence >= 0)
);

CREATE INDEX ix_delivery_status_next_attempt
    ON webhook_delivery(status, next_attempt_at);
CREATE INDEX ix_delivery_endpoint_created
    ON webhook_delivery(endpoint_id, created_at DESC);

CREATE TABLE outbox_message (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES webhook_delivery(id),
    dispatch_sequence INTEGER NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT,
    CONSTRAINT uq_outbox_delivery_dispatch UNIQUE (delivery_id, dispatch_sequence)
);

CREATE INDEX ix_outbox_unpublished
    ON outbox_message(created_at)
    WHERE published_at IS NULL;

CREATE TABLE dead_letter (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL UNIQUE REFERENCES webhook_delivery(id),
    reason TEXT NOT NULL,
    failed_at TIMESTAMPTZ NOT NULL,
    replayed_at TIMESTAMPTZ,
    replay_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT ck_dead_letter_replay_count CHECK (replay_count >= 0)
);
