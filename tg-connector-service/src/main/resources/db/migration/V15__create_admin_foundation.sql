ALTER TABLE balance_credit_transactions
    DROP CONSTRAINT chk_balance_credit_transactions_stars_positive;

ALTER TABLE balance_credit_transactions
    ADD CONSTRAINT chk_balance_credit_transactions_stars_non_negative
        CHECK (stars_amount >= 0);

CREATE TABLE admin_actions (
    id UUID PRIMARY KEY,
    operation_key VARCHAR(255) NOT NULL,
    admin_tg_user_id BIGINT NOT NULL,
    admin_username VARCHAR(255),
    action_type VARCHAR(64) NOT NULL,
    target_curator_id UUID,
    target_tg_chat_id BIGINT,
    amount NUMERIC(38, 2),
    reason TEXT,
    details TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_admin_actions_operation_key UNIQUE (operation_key),
    CONSTRAINT fk_admin_actions_target_curator
        FOREIGN KEY (target_curator_id) REFERENCES curators (id),
    CONSTRAINT chk_admin_actions_amount_positive
        CHECK (amount IS NULL OR amount > 0)
);

CREATE INDEX idx_admin_actions_admin_created
    ON admin_actions (admin_tg_user_id, created_at DESC);

CREATE INDEX idx_admin_actions_target_created
    ON admin_actions (target_curator_id, created_at DESC);
