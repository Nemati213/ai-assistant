CREATE TABLE kafka_dead_letters (
    id UUID PRIMARY KEY,
    dlt_topic VARCHAR(255) NOT NULL,
    dlt_partition INTEGER NOT NULL,
    dlt_offset BIGINT NOT NULL,
    original_topic VARCHAR(255) NOT NULL,
    event_key TEXT,
    payload TEXT NOT NULL,
    request_id VARCHAR(255),
    event_id VARCHAR(255),
    config_version VARCHAR(255),
    exception_class TEXT,
    exception_message TEXT,
    exception_stacktrace TEXT,
    retry_attempt INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    next_retry_at TIMESTAMP(6) WITH TIME ZONE,
    retried_at TIMESTAMP(6) WITH TIME ZONE,
    last_retry_error TEXT,
    notified_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT uk_kafka_dead_letter_source
        UNIQUE (dlt_topic, dlt_partition, dlt_offset),
    CONSTRAINT chk_kafka_dead_letter_retry_attempt
        CHECK (retry_attempt >= 0)
);

CREATE INDEX idx_kafka_dead_letters_retry
    ON kafka_dead_letters (next_retry_at, received_at)
    WHERE status IN ('PENDING', 'PUBLISH_FAILED');

CREATE INDEX idx_kafka_dead_letters_request_id
    ON kafka_dead_letters (request_id);
