ALTER TABLE workflow_states
    ADD COLUMN provider_cost_usd NUMERIC(20, 10);

ALTER TABLE workflow_states
    ADD COLUMN credits_to_charge NUMERIC(38, 2);

ALTER TABLE workflow_states
    ADD COLUMN credits_per_usd NUMERIC(38, 2);

ALTER TABLE workflow_states
    ADD COLUMN minimum_charge NUMERIC(38, 2);

ALTER TABLE workflow_states
    ADD CONSTRAINT chk_workflow_provider_cost_non_negative
        CHECK (provider_cost_usd IS NULL OR provider_cost_usd >= 0);

ALTER TABLE workflow_states
    ADD CONSTRAINT chk_workflow_credits_to_charge_non_negative
        CHECK (credits_to_charge IS NULL OR credits_to_charge >= 0);
