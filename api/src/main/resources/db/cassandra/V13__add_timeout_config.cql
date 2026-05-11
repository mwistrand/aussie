-- Add timeout_config field for per-service request/connect timeout overrides
-- Stores timeout settings as JSON

USE aussie;

ALTER TABLE service_registrations ADD timeout_config text;
