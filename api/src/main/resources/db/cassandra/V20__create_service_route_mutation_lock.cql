-- Serializes route ownership validation and the corresponding service mutation across gateway replicas.

USE aussie;

CREATE TABLE IF NOT EXISTS service_route_mutation_lock (
    scope text PRIMARY KEY,
    owner uuid
);
