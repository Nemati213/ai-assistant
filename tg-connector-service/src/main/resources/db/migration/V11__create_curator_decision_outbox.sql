CREATE TABLE curator_decision_requests (
    request_id UUID PRIMARY KEY,
    tg_chat_id BIGINT NOT NULL,
    vk_group_id VARCHAR(255) NOT NULL,
    student_question TEXT NOT NULL,
    current_answer TEXT NOT NULL,
    tokens_used INTEGER NOT NULL,
    credits_to_charge NUMERIC(38, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    revision INTEGER NOT NULL DEFAULT 0,
    approval_message_id INTEGER,
    edit_prompt_message_id INTEGER,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_curator_decision_tokens_non_negative
        CHECK (tokens_used >= 0),
    CONSTRAINT chk_curator_decision_credits_non_negative
        CHECK (credits_to_charge >= 0),
    CONSTRAINT chk_curator_decision_revision_non_negative
        CHECK (revision >= 0)
);

CREATE INDEX idx_curator_decision_edit_reply
    ON curator_decision_requests (tg_chat_id, edit_prompt_message_id)
    WHERE status = 'AWAITING_EDIT';

CREATE TABLE curator_decision_outbox (
    event_id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_error TEXT,
    CONSTRAINT uk_curator_decision_outbox_request UNIQUE (request_id),
    CONSTRAINT fk_curator_decision_outbox_request
        FOREIGN KEY (request_id)
        REFERENCES curator_decision_requests (request_id)
);

CREATE INDEX idx_curator_decision_outbox_ready
    ON curator_decision_outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL;
