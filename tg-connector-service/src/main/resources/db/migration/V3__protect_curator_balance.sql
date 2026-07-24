ALTER TABLE curators
    ADD CONSTRAINT chk_curators_balance_tokens_non_negative
        CHECK (balance_tokens >= 0);
