CREATE TABLE IF NOT EXISTS vk_group_credentials (
    vk_group_id VARCHAR(255) PRIMARY KEY,
    vk_token TEXT NOT NULL,
    vk_secret VARCHAR(255) NOT NULL,
    vk_confirmation_code VARCHAR(255) NOT NULL,
    callback_server_id BIGINT
);

ALTER TABLE vk_group_credentials
    ADD COLUMN IF NOT EXISTS callback_server_id BIGINT;
