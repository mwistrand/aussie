-- Add sampling_config field for service-level OTel sampling configuration
-- Stores sampling settings as JSON

USE aussie;

ALTER TABLE service_registrations ADD sampling_config text;
