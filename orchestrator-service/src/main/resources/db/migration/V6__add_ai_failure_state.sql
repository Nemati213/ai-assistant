ALTER TABLE workflow_states
    ADD COLUMN ai_error TEXT;

ALTER TABLE workflow_states
    ADD COLUMN ai_failed_at TIMESTAMP(6) WITH TIME ZONE;
