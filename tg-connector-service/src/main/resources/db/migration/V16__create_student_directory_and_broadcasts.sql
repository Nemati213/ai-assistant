CREATE TABLE curator_students (
    id UUID PRIMARY KEY,
    vk_group_id VARCHAR(255) NOT NULL,
    vk_user_id VARCHAR(255) NOT NULL,
    direct_vk_chat_id VARCHAR(255),
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    display_name VARCHAR(511),
    first_seen_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_seen_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_inbound_at TIMESTAMP(6) WITH TIME ZONE,
    CONSTRAINT fk_curator_students_group
        FOREIGN KEY (vk_group_id)
        REFERENCES curator_vk_groups (vk_group_id)
        ON DELETE CASCADE,
    CONSTRAINT uk_curator_students_group_user
        UNIQUE (vk_group_id, vk_user_id)
);

CREATE INDEX idx_curator_students_group_directory
    ON curator_students (
        vk_group_id,
        LOWER(COALESCE(display_name, '')),
        vk_user_id
    )
    WHERE direct_vk_chat_id IS NOT NULL;

CREATE TABLE broadcast_campaigns (
    id UUID PRIMARY KEY,
    curator_id UUID NOT NULL,
    tg_chat_id BIGINT NOT NULL,
    vk_group_id VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    message_template TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_broadcast_campaign_curator
        FOREIGN KEY (curator_id)
        REFERENCES curators (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_broadcast_campaign_group
        FOREIGN KEY (vk_group_id)
        REFERENCES curator_vk_groups (vk_group_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_broadcast_campaign_status
        CHECK (
            status IN (
                'SELECTING',
                'AWAITING_TEXT',
                'READY',
                'SENDING',
                'COMPLETED',
                'PARTIAL_FAILED',
                'CANCELLED'
            )
        )
);

CREATE UNIQUE INDEX uk_broadcast_campaign_active_curator
    ON broadcast_campaigns (tg_chat_id)
    WHERE status IN ('SELECTING', 'AWAITING_TEXT', 'READY', 'SENDING');

CREATE INDEX idx_broadcast_campaigns_curator_created
    ON broadcast_campaigns (curator_id, created_at DESC);

CREATE TABLE broadcast_recipients (
    request_id UUID PRIMARY KEY,
    campaign_id UUID NOT NULL,
    student_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    rendered_text TEXT,
    vk_message_id BIGINT,
    last_error TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_broadcast_recipient_campaign
        FOREIGN KEY (campaign_id)
        REFERENCES broadcast_campaigns (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_broadcast_recipient_student
        FOREIGN KEY (student_id)
        REFERENCES curator_students (id)
        ON DELETE CASCADE,
    CONSTRAINT uk_broadcast_recipient_student
        UNIQUE (campaign_id, student_id),
    CONSTRAINT chk_broadcast_recipient_status
        CHECK (status IN ('SELECTED', 'QUEUED', 'SENT', 'FAILED'))
);

CREATE INDEX idx_broadcast_recipients_campaign_status
    ON broadcast_recipients (campaign_id, status);

CREATE TABLE broadcast_outbox (
    event_id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    partition_key VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP(6) WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_error TEXT,
    CONSTRAINT uk_broadcast_outbox_request UNIQUE (request_id),
    CONSTRAINT fk_broadcast_outbox_recipient
        FOREIGN KEY (request_id)
        REFERENCES broadcast_recipients (request_id)
        ON DELETE CASCADE
);

CREATE INDEX idx_broadcast_outbox_ready
    ON broadcast_outbox (next_attempt_at, created_at)
    WHERE published_at IS NULL;
