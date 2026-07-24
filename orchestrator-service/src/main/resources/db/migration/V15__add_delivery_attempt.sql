ALTER TABLE workflow_states
    ADD COLUMN delivery_attempt INTEGER NOT NULL DEFAULT 0;

UPDATE workflow_states
SET delivery_attempt = 1
WHERE status IN (
    'SENDING_TO_STUDENT',
    'COMPLETED',
    'REFUND_PENDING',
    'DELIVERY_FAILED_REFUNDED',
    'REFUND_FAILED',
    'DELIVERY_FAILED'
);

ALTER TABLE workflow_states
    ADD CONSTRAINT chk_workflow_delivery_attempt_non_negative
        CHECK (delivery_attempt >= 0);
