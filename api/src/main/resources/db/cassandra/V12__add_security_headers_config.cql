-- Add security_headers_config field for per-service security header overrides
-- Stores header override settings as JSON

USE aussie;

ALTER TABLE service_registrations ADD security_headers_config text;
