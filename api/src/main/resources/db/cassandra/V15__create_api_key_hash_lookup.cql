-- Direct partition-key lookup for API-key authentication.

CREATE TABLE IF NOT EXISTS api_keys_by_hash (
    key_hash text PRIMARY KEY,
    key_id text,
    encrypted_data text,
    created_at timestamp,
    updated_at timestamp
);

DROP INDEX IF EXISTS api_keys_hash_idx;
