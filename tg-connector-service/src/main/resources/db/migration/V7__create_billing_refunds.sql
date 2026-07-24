CREATE TABLE billing_refunds (
    request_id UUID PRIMARY KEY,
    curator_id UUID,
    credits NUMERIC(38, 2) NOT NULL,
    status VARCHAR(255) NOT NULL,
    balance_after NUMERIC(38, 2),
    reason TEXT,
    error_message TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    result_published_at TIMESTAMP(6) WITH TIME ZONE,
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    next_publish_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_publish_error TEXT,
    CONSTRAINT fk_billing_refunds_curator
        FOREIGN KEY (curator_id) REFERENCES curators (id),
    CONSTRAINT chk_billing_refunds_credits_non_negative
        CHECK (credits >= 0)
);

CREATE INDEX idx_billing_refunds_pending_results
    ON billing_refunds (next_publish_attempt_at, created_at)
    WHERE result_published_at IS NULL;
