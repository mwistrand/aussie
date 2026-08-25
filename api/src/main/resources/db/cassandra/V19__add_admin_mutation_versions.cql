-- Add optimistic-concurrency versions to mutable administrative records.

ALTER TABLE roles ADD IF NOT EXISTS version bigint;
ALTER TABLE api_keys ADD IF NOT EXISTS version bigint;
