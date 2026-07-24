CREATE TABLE IF NOT EXISTS workflow_states (
    request_id UUID PRIMARY KEY,
    vk_chat_id VARCHAR(255) NOT NULL,
    vk_group_id VARCHAR(255) NOT NULL,
    student_question TEXT NOT NULL,
    ai_suggested_answer TEXT,
    vk_message_id BIGINT,
    delivery_error TEXT,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    status VARCHAR(255) NOT NULL
);

ALTER TABLE workflow_states
    ADD COLUMN IF NOT EXISTS vk_message_id BIGINT;

ALTER TABLE workflow_states
    ADD COLUMN IF NOT EXISTS delivery_error TEXT;

ALTER TABLE workflow_states
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP(6) WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_workflow_states_vk_group_id
    ON workflow_states (vk_group_id);

CREATE INDEX IF NOT EXISTS idx_workflow_states_status
    ON workflow_states (status);

CREATE TABLE IF NOT EXISTS workflow_state_photos (
    request_id UUID NOT NULL,
    photo_url TEXT,
    CONSTRAINT fk_workflow_state_photos_workflow
        FOREIGN KEY (request_id) REFERENCES workflow_states (request_id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_workflow_state_photos_request_id
    ON workflow_state_photos (request_id);

CREATE TABLE IF NOT EXISTS vk_group_prompts (
    vk_group_id VARCHAR(255) PRIMARY KEY,
    system_prompt TEXT NOT NULL
);
