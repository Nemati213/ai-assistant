ALTER TABLE workflow_states
    ADD COLUMN status_changed_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE workflow_states
    ADD COLUMN recovery_attempts INTEGER NOT NULL DEFAULT 0;

UPDATE workflow_states
SET status_changed_at = COALESCE(completed_at, CURRENT_TIMESTAMP)
WHERE status_changed_at IS NULL;

ALTER TABLE workflow_states
    ALTER COLUMN status_changed_at SET NOT NULL;

CREATE INDEX idx_workflow_states_watchdog
    ON workflow_states (status, recovery_attempts, status_changed_at);

ALTER TABLE workflow_states
    ADD CONSTRAINT chk_workflow_recovery_attempts_non_negative
        CHECK (recovery_attempts >= 0);
