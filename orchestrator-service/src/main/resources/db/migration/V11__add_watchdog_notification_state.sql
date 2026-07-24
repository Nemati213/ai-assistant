ALTER TABLE workflow_states
    ADD COLUMN recovery_exhausted_notified_at TIMESTAMP(6) WITH TIME ZONE;

CREATE INDEX idx_workflow_states_recovery_exhausted
    ON workflow_states (status, recovery_attempts)
    WHERE recovery_exhausted_notified_at IS NULL;
