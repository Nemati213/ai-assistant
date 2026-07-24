CREATE TABLE ai_generation_requests (
    request_id UUID PRIMARY KEY,
    command_fingerprint VARCHAR(64) NOT NULL,
    vk_chat_id VARCHAR(255) NOT NULL,
    vk_group_id VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    answer_text TEXT,
    tokens_used INTEGER,
    provider_cost_usd NUMERIC(20, 10),
    error_message TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    started_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    result_published_at TIMESTAMP(6) WITH TIME ZONE,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    next_publish_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_publish_error TEXT,
    CONSTRAINT chk_ai_generation_tokens_non_negative
        CHECK (tokens_used IS NULL OR tokens_used >= 0),
    CONSTRAINT chk_ai_generation_cost_non_negative
        CHECK (provider_cost_usd IS NULL OR provider_cost_usd >= 0)
);

CREATE INDEX idx_ai_generation_pending_results
    ON ai_generation_requests (next_publish_attempt_at, created_at)
    WHERE result_published_at IS NULL
      AND status IN ('COMPLETED', 'FAILED');

CREATE INDEX idx_ai_generation_stale_processing
    ON ai_generation_requests (started_at)
    WHERE status = 'PROCESSING';
