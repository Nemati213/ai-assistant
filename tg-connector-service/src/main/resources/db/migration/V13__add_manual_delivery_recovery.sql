ALTER TABLE curator_intake_requests
    ADD COLUMN delivery_attempt INTEGER NOT NULL DEFAULT 0;

ALTER TABLE curator_intake_requests
    ADD COLUMN last_delivery_error TEXT;

ALTER TABLE curator_intake_requests
    ADD COLUMN failure_message_id INTEGER;

ALTER TABLE curator_intake_requests
    ADD CONSTRAINT chk_curator_intake_delivery_attempt_non_negative
        CHECK (delivery_attempt >= 0);

ALTER TABLE curator_intake_outbox
    ADD COLUMN deduplication_key VARCHAR(255);

UPDATE curator_intake_outbox
SET deduplication_key = request_id::text || ':INITIAL'
WHERE deduplication_key IS NULL;

ALTER TABLE curator_intake_outbox
    ALTER COLUMN deduplication_key SET NOT NULL;

ALTER TABLE curator_intake_outbox
    DROP CONSTRAINT uk_curator_intake_outbox_request;

ALTER TABLE curator_intake_outbox
    ADD CONSTRAINT uk_curator_intake_outbox_deduplication
        UNIQUE (deduplication_key);
