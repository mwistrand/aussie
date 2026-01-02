-- Translation config version storage
-- Stores versioned translation configurations with history for auditing and rollback

CREATE TABLE IF NOT EXISTS translation_config_versions (
    id text PRIMARY KEY,
    version int,
    config_json text,
    active boolean,
    created_by text,
    created_at timestamp,
    comment text
);

-- Index for finding versions by sequential version number
CREATE INDEX IF NOT EXISTS translation_config_versions_version_idx
    ON translation_config_versions (version);

-- Separate table for metadata to avoid contention
-- This table stores global state like active version ID and version counter
CREATE TABLE IF NOT EXISTS translation_config_metadata (
    key text PRIMARY KEY,
    value text,
    updated_at timestamp
);
