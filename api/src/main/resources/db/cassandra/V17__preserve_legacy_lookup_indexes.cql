-- Preserve legacy lookups while older nodes can still participate in rolling upgrades.
-- The API migration runner backfills both lookup tables after these statements succeed.

CREATE INDEX IF NOT EXISTS api_keys_hash_idx ON api_keys (key_hash);

CREATE INDEX IF NOT EXISTS translation_config_versions_version_idx
    ON translation_config_versions (version);
