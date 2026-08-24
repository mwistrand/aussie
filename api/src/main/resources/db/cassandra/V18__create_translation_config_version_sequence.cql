-- Ordered, bounded pagination for translation configuration history.

CREATE TABLE IF NOT EXISTS translation_config_versions_by_sequence (
    scope text,
    version int,
    id text,
    config_json text,
    created_by text,
    created_at timestamp,
    comment text,
    PRIMARY KEY (scope, version)
) WITH CLUSTERING ORDER BY (version DESC);
