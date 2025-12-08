-- Add CORS configuration column to service_registrations table
-- Stores per-service CORS configuration as JSON

USE aussie;

ALTER TABLE service_registrations ADD cors_config text;
