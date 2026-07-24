CREATE TABLE students (
    id UUID PRIMARY KEY,
    vk_group_id VARCHAR(255) NOT NULL,
    vk_user_id VARCHAR(255) NOT NULL,
    latest_vk_chat_id VARCHAR(255) NOT NULL,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    display_name VARCHAR(511),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_students_vk_group_user
        UNIQUE (vk_group_id, vk_user_id)
);

CREATE TABLE student_messages (
    id UUID PRIMARY KEY,
    student_id UUID NOT NULL,
    request_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    message_text TEXT NOT NULL,
    photo_urls_json TEXT NOT NULL DEFAULT '[]',
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_student_messages_student
        FOREIGN KEY (student_id) REFERENCES students (id) ON DELETE CASCADE,
    CONSTRAINT uk_student_messages_request_role
        UNIQUE (request_id, role),
    CONSTRAINT chk_student_messages_role
        CHECK (role IN ('USER', 'ASSISTANT'))
);

CREATE INDEX idx_student_messages_history
    ON student_messages (student_id, created_at DESC, id DESC);

CREATE INDEX idx_students_updated_at
    ON students (updated_at DESC);
