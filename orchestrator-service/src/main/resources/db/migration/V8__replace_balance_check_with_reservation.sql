ALTER TABLE workflow_states
    RENAME COLUMN balance_at_check TO available_balance_after_reservation;

ALTER TABLE workflow_states
    RENAME COLUMN balance_check_error TO reservation_error;

ALTER TABLE workflow_states
    ADD COLUMN reserved_credits NUMERIC(38, 2);

ALTER TABLE workflow_states
    ADD COLUMN reservation_expires_at TIMESTAMP(6) WITH TIME ZONE;

UPDATE workflow_states
SET status = 'RESERVATION_PENDING'
WHERE status = 'BALANCE_CHECK_PENDING';

UPDATE workflow_states
SET status = 'RESERVATION_BLOCKED'
WHERE status = 'BALANCE_BLOCKED';

ALTER TABLE workflow_states
    ADD CONSTRAINT chk_workflow_reserved_credits_positive
        CHECK (reserved_credits IS NULL OR reserved_credits > 0);
