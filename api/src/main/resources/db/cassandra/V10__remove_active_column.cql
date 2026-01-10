-- Remove unused active column from translation_config_versions table
-- The active version is determined by the active_version_id in translation_config_metadata,
-- not by the row-level active boolean. This column was never read and could become stale.

ALTER TABLE translation_config_versions DROP active;
