ALTER TABLE curator_vk_groups
    ADD COLUMN config_version BIGINT NOT NULL DEFAULT 1;

ALTER TABLE curator_vk_groups
    ADD COLUMN pending_config_event_id UUID;

CREATE SEQUENCE vk_group_config_version_seq;

UPDATE curator_vk_groups
SET config_version = nextval('vk_group_config_version_seq');

CREATE TABLE vk_group_config_outbox (
    event_id UUID PRIMARY KEY,
    vk_group_id VARCHAR(255) NOT NULL,
    config_version BIGINT NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_error TEXT,
    CONSTRAINT uk_vk_group_config_outbox_version
        UNIQUE (vk_group_id, config_version)
);

CREATE INDEX idx_vk_group_config_outbox_ready
    ON vk_group_config_outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL;
