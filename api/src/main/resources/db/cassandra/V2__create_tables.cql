-- Service Registrations Table
-- Stores all registered services with their configurations

USE aussie;

CREATE TABLE IF NOT EXISTS service_registrations (
    service_id text PRIMARY KEY,
    display_name text,
    base_url text,
    route_prefix text,
    default_visibility text,
    visibility_rules text,      -- JSON array of VisibilityRule
    endpoints text,             -- JSON array of EndpointConfig
    access_config text,         -- JSON object of ServiceAccessConfig (nullable)
    created_at timestamp,
    updated_at timestamp
);

-- Index for route prefix lookups (useful for finding services by prefix)
CREATE INDEX IF NOT EXISTS idx_route_prefix
ON service_registrations (route_prefix);

-- Index for finding services by display name
CREATE INDEX IF NOT EXISTS idx_display_name
ON service_registrations (display_name);
