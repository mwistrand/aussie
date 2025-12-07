-- Add default_auth_required column to service_registrations
-- This column controls whether pass-through requests require authentication

USE aussie;

ALTER TABLE service_registrations
ADD default_auth_required boolean;
