ALTER TABLE vk_outgoing_deliveries
    ADD COLUMN delivery_error_category VARCHAR(64);

ALTER TABLE vk_outgoing_deliveries
    ADD COLUMN automatic_retry_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE vk_outgoing_deliveries
    ADD COLUMN next_delivery_attempt_at TIMESTAMP(6) WITH TIME ZONE;

ALTER TABLE vk_outgoing_deliveries
    ADD CONSTRAINT chk_vk_delivery_automatic_retries_non_negative
        CHECK (automatic_retry_attempts >= 0);

CREATE INDEX idx_vk_outgoing_delivery_retry_ready
    ON vk_outgoing_deliveries (next_delivery_attempt_at, updated_at)
    WHERE status = 'RETRY_PENDING';

CREATE INDEX idx_vk_outgoing_delivery_processing
    ON vk_outgoing_deliveries (updated_at)
    WHERE status = 'PROCESSING';
