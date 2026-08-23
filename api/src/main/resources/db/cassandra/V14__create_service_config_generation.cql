-- Durable generation used by gateway instances to detect missed configuration events.

USE aussie;

CREATE TABLE IF NOT EXISTS service_config_generation (
    scope text PRIMARY KEY,
    generation bigint,
    updated_at timestamp
);

INSERT INTO service_config_generation (scope, generation, updated_at)
VALUES ('global', 0, toTimestamp(now()))
IF NOT EXISTS;
