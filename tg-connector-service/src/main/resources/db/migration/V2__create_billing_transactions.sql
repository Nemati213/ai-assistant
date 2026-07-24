CREATE TABLE billing_transactions (
    request_id UUID PRIMARY KEY,
    curator_id UUID NOT NULL,
    vk_group_id VARCHAR(255) NOT NULL,
    tokens INTEGER NOT NULL,
    status VARCHAR(255) NOT NULL,
    balance_after NUMERIC(38, 2) NOT NULL,
    error_message TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_billing_transactions_curator
        FOREIGN KEY (curator_id) REFERENCES curators (id),
    CONSTRAINT chk_billing_transactions_tokens_non_negative
        CHECK (tokens >= 0)
);

CREATE INDEX idx_billing_transactions_curator_created
    ON billing_transactions (curator_id, created_at);
