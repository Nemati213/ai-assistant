ALTER TABLE vk_group_prompts
    ADD COLUMN config_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE vk_group_prompts
    ADD COLUMN last_config_event_id UUID;
