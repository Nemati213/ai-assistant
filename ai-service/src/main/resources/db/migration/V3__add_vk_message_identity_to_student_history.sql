ALTER TABLE student_messages
    ADD COLUMN external_message_id BIGINT;

ALTER TABLE student_messages
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'AI_WORKFLOW';

CREATE UNIQUE INDEX uk_student_messages_vk_message
    ON student_messages (student_id, role, external_message_id)
    WHERE external_message_id IS NOT NULL;
