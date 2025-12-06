-- API Keys Table
-- Stores API keys for authentication with optional encryption at rest

USE aussie;

CREATE TABLE IF NOT EXISTS api_keys (
    key_id text PRIMARY KEY,
    key_hash text,
    encrypted_data text,
    created_at timestamp,
    updated_at timestamp
);

-- Index for key hash lookups during authentication
CREATE INDEX IF NOT EXISTS api_keys_hash_idx ON api_keys (key_hash);
