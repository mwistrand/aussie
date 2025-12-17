-- Migration: V8__create_roles_table.cql
-- Description: Creates the roles table for RBAC roles-to-permission mapping

USE aussie;

CREATE TABLE IF NOT EXISTS roles (
    role_id text PRIMARY KEY,
    encrypted_data text,
    created_at timestamp,
    updated_at timestamp
);
