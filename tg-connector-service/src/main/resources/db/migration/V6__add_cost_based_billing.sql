ALTER TABLE billing_transactions
    RENAME COLUMN tokens TO ai_tokens;

ALTER TABLE billing_transactions
    ADD COLUMN provider_cost_usd NUMERIC(20, 10) NOT NULL DEFAULT 0;

ALTER TABLE billing_transactions
    ADD COLUMN credits_charged NUMERIC(38, 2);

ALTER TABLE billing_transactions
    ADD COLUMN credits_per_usd NUMERIC(38, 2) NOT NULL DEFAULT 0;

ALTER TABLE billing_transactions
    ADD COLUMN minimum_charge NUMERIC(38, 2) NOT NULL DEFAULT 0;

UPDATE billing_transactions
SET credits_charged = ai_tokens
WHERE credits_charged IS NULL;

ALTER TABLE billing_transactions
    ALTER COLUMN credits_charged SET NOT NULL;

ALTER TABLE billing_transactions
    ADD CONSTRAINT chk_billing_provider_cost_non_negative
        CHECK (provider_cost_usd >= 0);

ALTER TABLE billing_transactions
    ADD CONSTRAINT chk_billing_credits_charged_non_negative
        CHECK (credits_charged >= 0);
