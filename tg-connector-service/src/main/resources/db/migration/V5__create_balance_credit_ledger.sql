CREATE TABLE balance_credit_transactions (
    id UUID PRIMARY KEY,
    curator_id UUID NOT NULL,
    source VARCHAR(255) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    credits NUMERIC(38, 2) NOT NULL,
    stars_amount INTEGER NOT NULL,
    currency VARCHAR(16) NOT NULL,
    invoice_payload VARCHAR(255) NOT NULL,
    balance_after NUMERIC(38, 2) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_balance_credit_transactions_curator
        FOREIGN KEY (curator_id) REFERENCES curators (id),
    CONSTRAINT uq_balance_credit_transactions_external_id
        UNIQUE (external_id),
    CONSTRAINT chk_balance_credit_transactions_credits_positive
        CHECK (credits > 0),
    CONSTRAINT chk_balance_credit_transactions_stars_positive
        CHECK (stars_amount > 0)
);

CREATE INDEX idx_balance_credit_transactions_curator_created
    ON balance_credit_transactions (curator_id, created_at);
