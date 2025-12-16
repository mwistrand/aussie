-- Migration: V8__create_groups_table.cql
-- Description: Creates the groups table for RBAC group-to-permission mapping

CREATE TABLE IF NOT EXISTS groups (
    group_id text PRIMARY KEY,
    encrypted_data text,
    created_at timestamp,
    updated_at timestamp
);

-- Default groups for common use cases
-- Note: These are inserted with encrypted_data placeholder; actual data will be
-- populated by the application on first use or via admin API.
