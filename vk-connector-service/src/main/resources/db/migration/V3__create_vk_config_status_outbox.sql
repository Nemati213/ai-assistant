ALTER TABLE vk_group_credentials
    ADD COLUMN config_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE vk_group_credentials
    ADD COLUMN last_config_event_id UUID;

CREATE TABLE vk_group_config_status_outbox (
    event_id UUID PRIMARY KEY,
    config_version BIGINT NOT NULL,
    vk_group_id VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_error TEXT
);

CREATE INDEX idx_vk_config_status_outbox_ready
    ON vk_group_config_status_outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL;
