-- Direct partition-key lookup for versioned translation configurations.

CREATE TABLE IF NOT EXISTS translation_config_versions_by_number (
    version int PRIMARY KEY,
    id text,
    config_json text,
    created_by text,
    created_at timestamp,
    comment text
);

DROP INDEX IF EXISTS translation_config_versions_version_idx;
