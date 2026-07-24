ALTER TABLE workflow_states
    ADD COLUMN refunded_credits NUMERIC(38, 2);

ALTER TABLE workflow_states
    ADD COLUMN refund_error TEXT;

ALTER TABLE workflow_states
    ADD COLUMN refunded_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE workflow_states
    ADD CONSTRAINT chk_workflow_refunded_credits_non_negative
        CHECK (refunded_credits IS NULL OR refunded_credits >= 0);
