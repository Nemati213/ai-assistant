CREATE TABLE vk_webhook_outbox (
    id UUID PRIMARY KEY,
    deduplication_key VARCHAR(255) NOT NULL,
    request_id UUID NOT NULL,
    topic VARCHAR(255) NOT NULL,
    event_key VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_error TEXT,
    CONSTRAINT uk_vk_webhook_outbox_deduplication
        UNIQUE (deduplication_key),
    CONSTRAINT chk_vk_webhook_outbox_attempts_non_negative
        CHECK (attempts >= 0)
);

CREATE INDEX idx_vk_webhook_outbox_ready
    ON vk_webhook_outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL;
