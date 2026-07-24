ALTER TABLE workflow_states
    ADD COLUMN balance_at_check NUMERIC(38, 2);

ALTER TABLE workflow_states
    ADD COLUMN balance_check_error TEXT;
