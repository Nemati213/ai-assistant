CREATE TABLE IF NOT EXISTS subjects (
    id UUID PRIMARY KEY,
    code VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    system_prompt TEXT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_subjects_code
    ON subjects (code);

CREATE TABLE IF NOT EXISTS curators (
    id UUID PRIMARY KEY,
    tg_chat_id BIGINT NOT NULL,
    username VARCHAR(255),
    subject_id UUID NOT NULL,
    balance_tokens NUMERIC(38, 2) NOT NULL,
    CONSTRAINT fk_curators_subject
        FOREIGN KEY (subject_id) REFERENCES subjects (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_curators_tg_chat_id
    ON curators (tg_chat_id);

CREATE INDEX IF NOT EXISTS idx_curators_subject_id
    ON curators (subject_id);

CREATE TABLE IF NOT EXISTS curator_vk_groups (
    id UUID PRIMARY KEY,
    curator_id UUID NOT NULL,
    vk_group_id VARCHAR(255) NOT NULL,
    vk_token TEXT NOT NULL,
    vk_secret VARCHAR(255) NOT NULL,
    vk_confirmation_code VARCHAR(255) NOT NULL,
    status VARCHAR(255),
    last_error TEXT,
    CONSTRAINT fk_curator_vk_groups_curator
        FOREIGN KEY (curator_id) REFERENCES curators (id) ON DELETE CASCADE
);

ALTER TABLE curator_vk_groups
    ADD COLUMN IF NOT EXISTS status VARCHAR(255);

ALTER TABLE curator_vk_groups
    ADD COLUMN IF NOT EXISTS last_error TEXT;

UPDATE curator_vk_groups
SET status = 'ACTIVE'
WHERE status IS NULL;

ALTER TABLE curator_vk_groups
    ALTER COLUMN status SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_curator_vk_groups_vk_group_id
    ON curator_vk_groups (vk_group_id);

CREATE INDEX IF NOT EXISTS idx_curator_vk_groups_curator_id
    ON curator_vk_groups (curator_id);

CREATE TABLE IF NOT EXISTS registration_contexts (
    tg_chat_id BIGINT PRIMARY KEY,
    state VARCHAR(255) NOT NULL,
    subject_id UUID,
    username VARCHAR(255),
    vk_group_id VARCHAR(255),
    vk_token TEXT,
    vk_secret VARCHAR(255),
    vk_confirmation_code VARCHAR(255)
);

ALTER TABLE registration_contexts
    ADD COLUMN IF NOT EXISTS username VARCHAR(255);
