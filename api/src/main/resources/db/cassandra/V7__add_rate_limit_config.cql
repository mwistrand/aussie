-- Add rate_limit_config field for service-level rate limiting configuration
-- Stores rate limiting settings as JSON, including per-endpoint overrides

USE aussie;

ALTER TABLE service_registrations ADD rate_limit_config text;
