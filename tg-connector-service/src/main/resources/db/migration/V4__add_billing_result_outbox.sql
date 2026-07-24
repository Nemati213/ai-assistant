ALTER TABLE billing_transactions
    ADD COLUMN result_published_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE billing_transactions
    ADD COLUMN publish_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE billing_transactions
    ADD COLUMN next_publish_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE billing_transactions
    ADD COLUMN last_publish_error TEXT;

CREATE INDEX idx_billing_transactions_pending_results
    ON billing_transactions (next_publish_attempt_at, created_at)
    WHERE result_published_at IS NULL;
