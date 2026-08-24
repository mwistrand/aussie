# Cassandra migrations

Aussie applies the ordered, checksummed manifest in `api/src/main/resources/db/cassandra/`
when `CASSANDRA_RUN_MIGRATIONS=true`. The application runner is the
only supported migration path; `make migrate` starts the API and waits for readiness.

Each migration is claimed with a five-minute Cassandra LWT lease. A crashed runner
can therefore be replaced after the lease expires, while a live runner prevents a
second instance from applying the same version. Completed scripts are checked against
their recorded SHA-256 checksum. A changed script or a failed/unknown schema state
keeps the API unready instead of silently continuing.

Cassandra DDL is not transactional. Migrations are idempotent and safe to retry, but
there is no automatic down migration. To roll back an application release, restore
the compatible application version first; to undo a schema change, ship a new
forward-fix migration and preserve the existing data. Take a Cassandra snapshot
before destructive schema changes and test the forward-fix against a production-like
cluster before deployment.

The E2E suite applies the packaged artifact's manifest to Cassandra, verifies every
recorded migration is completed with a checksum, reruns the manifest as a no-op, and
applies the same scripts to a non-default keyspace. Unit coverage verifies that a
failed migration with an unchanged checksum can be reclaimed and safely retried;
there is intentionally no automatic down migration.
