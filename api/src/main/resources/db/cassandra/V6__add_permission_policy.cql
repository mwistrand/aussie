-- Add permission policy and version fields for service-level authorization
-- Version field enables optimistic locking for concurrent updates
-- Permission policy stores operation-to-claims mappings as JSON

USE aussie;

ALTER TABLE service_registrations ADD permission_policy text;
ALTER TABLE service_registrations ADD version bigint;
