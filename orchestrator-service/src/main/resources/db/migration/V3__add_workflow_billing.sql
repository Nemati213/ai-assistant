ALTER TABLE workflow_states
    ADD COLUMN tokens_used INTEGER;

ALTER TABLE workflow_states
    ADD COLUMN billing_error TEXT;

ALTER TABLE workflow_states
    ADD CONSTRAINT chk_workflow_states_tokens_used_non_negative
        CHECK (tokens_used IS NULL OR tokens_used >= 0);
