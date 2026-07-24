ALTER TABLE curator_vk_groups
    ALTER COLUMN vk_secret TYPE TEXT,
    ALTER COLUMN vk_confirmation_code TYPE TEXT;

ALTER TABLE registration_contexts
    ALTER COLUMN vk_secret TYPE TEXT,
    ALTER COLUMN vk_confirmation_code TYPE TEXT;
