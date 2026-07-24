ALTER TABLE workflow_states
    ADD COLUMN vk_user_id VARCHAR(255);

UPDATE workflow_states
SET vk_user_id = vk_chat_id
WHERE vk_user_id IS NULL;

ALTER TABLE workflow_states
    ALTER COLUMN vk_user_id SET NOT NULL;

CREATE INDEX idx_workflow_states_vk_group_user
    ON workflow_states (vk_group_id, vk_user_id);
