CREATE TABLE curator_intake_requests (
    request_id UUID PRIMARY KEY,
    tg_chat_id BIGINT NOT NULL,
    vk_group_id VARCHAR(255) NOT NULL,
    student_question TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    intake_message_id INTEGER,
    manual_prompt_message_id INTEGER,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_curator_intake_manual_reply
    ON curator_intake_requests (tg_chat_id, manual_prompt_message_id)
    WHERE status = 'AWAITING_MANUAL_REPLY';

CREATE TABLE curator_intake_outbox (
    event_id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_error TEXT,
    CONSTRAINT uk_curator_intake_outbox_request UNIQUE (request_id),
    CONSTRAINT fk_curator_intake_outbox_request
        FOREIGN KEY (request_id)
        REFERENCES curator_intake_requests (request_id)
);

CREATE INDEX idx_curator_intake_outbox_ready
    ON curator_intake_outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL;
