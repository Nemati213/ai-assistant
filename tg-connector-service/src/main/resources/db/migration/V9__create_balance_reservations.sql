ALTER TABLE curators
    ADD COLUMN reserved_tokens NUMERIC(38, 2) NOT NULL DEFAULT 0;

ALTER TABLE curators
    ADD CONSTRAINT chk_curators_reserved_tokens_non_negative
        CHECK (reserved_tokens >= 0);

ALTER TABLE curators
    ADD CONSTRAINT chk_curators_reserved_not_above_balance
        CHECK (reserved_tokens <= balance_tokens);

CREATE TABLE balance_reservations (
    request_id UUID PRIMARY KEY,
    curator_id UUID,
    vk_group_id VARCHAR(255) NOT NULL,
    reserved_credits NUMERIC(38, 2) NOT NULL,
    actual_credits NUMERIC(38, 2),
    status VARCHAR(255) NOT NULL,
    balance_at_reservation NUMERIC(38, 2),
    available_balance_after NUMERIC(38, 2),
    error_message TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT fk_balance_reservations_curator
        FOREIGN KEY (curator_id) REFERENCES curators (id),
    CONSTRAINT chk_balance_reservations_reserved_positive
        CHECK (reserved_credits > 0),
    CONSTRAINT chk_balance_reservations_actual_non_negative
        CHECK (actual_credits IS NULL OR actual_credits >= 0)
);

CREATE INDEX idx_balance_reservations_curator
    ON balance_reservations (curator_id);

CREATE INDEX idx_balance_reservations_expiration
    ON balance_reservations (expires_at)
    WHERE status = 'RESERVED';
