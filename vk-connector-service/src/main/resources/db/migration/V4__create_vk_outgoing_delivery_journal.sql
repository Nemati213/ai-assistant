CREATE TABLE vk_outgoing_deliveries (
    request_id UUID PRIMARY KEY,
    vk_chat_id VARCHAR(255) NOT NULL,
    vk_group_id VARCHAR(255) NOT NULL,
    message_text TEXT NOT NULL,
    delivery_attempt INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    vk_message_id BIGINT,
    delivery_error TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    result_published_at TIMESTAMP(6) WITH TIME ZONE,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    next_publish_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    publish_error TEXT,
    CONSTRAINT chk_vk_delivery_attempt_positive
        CHECK (delivery_attempt > 0),
    CONSTRAINT chk_vk_delivery_publish_attempts_non_negative
        CHECK (publish_attempts >= 0)
);

CREATE INDEX idx_vk_outgoing_delivery_publish_ready
    ON vk_outgoing_deliveries (next_publish_at, updated_at)
    WHERE result_published_at IS NULL
      AND status IN ('SUCCEEDED', 'FAILED');
