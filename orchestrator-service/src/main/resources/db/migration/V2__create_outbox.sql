CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    deduplication_key VARCHAR(255) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    event_key VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_error TEXT,
    CONSTRAINT uq_outbox_events_deduplication_key UNIQUE (deduplication_key)
);

CREATE INDEX idx_outbox_events_ready
    ON outbox_events (next_attempt_at, created_at)
    WHERE published_at IS NULL;

CREATE INDEX idx_outbox_events_published_at
    ON outbox_events (published_at)
    WHERE published_at IS NOT NULL;
