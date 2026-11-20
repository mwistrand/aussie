# Production deployment reference

The repository-owned Kubernetes base in [`deploy/kubernetes`](../../deploy/kubernetes/)
is the reference deployment. It assumes an external ingress/TLS controller,
Cassandra, Redis, telemetry collector, and secret manager. Replace the image
digest and endpoint placeholders in an environment overlay before applying it.

## Cassandra topology

Create the keyspace before deploying the gateway. The example below is a shape,
not a universal sizing recommendation; choose the datacenter names and
replication factors for the actual failure domains, and use at least two
replicas in every datacenter used by Aussie:

```sql
CREATE KEYSPACE aussie
WITH replication = {
  'class': 'NetworkTopologyStrategy',
  'datacenter1': 3
};
```

The production application never creates a keyspace. `CASSANDRA_CREATE_KEYSPACE`
must remain `false`. A readiness check rejects a missing keyspace,
`SimpleStrategy`, RF1, or an incomplete `schema_migrations` table. Use
`CASSANDRA_RUN_MIGRATIONS=true` only for a reviewed migration job after the
operator-created keyspace exists; the normal Deployment keeps it false.

Use `LOCAL_QUORUM` for the Cassandra request consistency level and keep the
configured local datacenter aligned with the keyspace topology. Take a snapshot
before destructive schema work. Cassandra migrations are forward-only; rollback
requires a compatible application image or a new forward-fix migration.

## Dependency versions and data protection

The repository qualifies the production reference with Cassandra `4.1.4` and
Redis `7.2.5` (see `tools/kubernetes_qualification.sh`). These are the tested
reference versions, not a promise that every managed-service version is
compatible. Validate provider upgrades with the same topology, migration, and
failure checks before rollout. Redis sessions, PKCE state, caches, rate-limit
buckets, and in-flight propagation state are not a substitute for Cassandra
backups; plan for their loss during a Redis restore and require users to
reauthenticate where necessary.

The platform owner is responsible for backup and restore execution:

1. Take Cassandra snapshots (or provider-equivalent backups) on a schedule
   that meets the service's data-loss budget, and test restoring into a separate
   cluster before using it for recovery.
2. Restore Cassandra with `NetworkTopologyStrategy`, the intended datacenter
   names, and a replication factor of at least two before starting Aussie.
3. Run the migration validation and a read-only smoke test against the restored
   cluster; do not use an image rollback as a schema rollback.
4. Use the Redis provider's durable backup feature only for data whose recovery
   matters operationally (for example, audit/idempotency continuity); transient
   session and cache state may be rebuilt.

This repository does not define an organization-wide SLA. A deployment is not
release-ready until its owner records explicit RPO/RTO targets for Cassandra and
Redis, the backup interval and retention, and the tested restore time. The
disposable qualification measures dependency recovery and gateway readiness;
it does not prove those provider-specific backup/restore targets.

## Secrets and rollout

Create the external secret named `aussie-production-secrets` with
`CASSANDRA_USERNAME`, `CASSANDRA_PASSWORD`, `REDIS_PASSWORD`, and
`AUTH_ENCRYPTION_KEY`. Keep it out of ConfigMaps, manifests, command lines, and
logs. The base NetworkPolicy permits the standard Kubernetes DNS pods and
in-cluster Cassandra, Redis, and OTLP gRPC telemetry only when their namespaces
carry `aussie.network/cassandra=true`, `aussie.network/redis=true`, and
`aussie.network/telemetry=true`, respectively. The base points
`OTEL_EXPORTER_OTLP_ENDPOINT` at a documentation-only collector hostname;
replace it with the collector service or use the external-dependencies overlay.
Production tracing is included in the released image and enabled by default;
set `AUSSIE_TELEMETRY_TRACING_ENABLED=false` in an environment overlay only
when the collector is intentionally disabled.
For managed Cassandra or Redis outside the cluster, use the
`deploy/kubernetes/overlays/external-dependencies` overlay, replacing its
documentation-only IP blocks with the provider endpoint CIDRs. The overlay
also includes a documentation-only telemetry address on TCP 4317. A namespace
selector cannot authorize external IPs. Add environment-specific egress rules
for approved dynamic upstream networks; they cannot be safely represented by a
single universal NetworkPolicy.

CORS is disabled in the base. Enable it only in an overlay that supplies
`AUSSIE_GATEWAY_CORS_ENABLED=true` and one or more real HTTPS origins.

Apply and verify the signed digest:

```sh
kubectl apply -k deploy/kubernetes/overlays/external-dependencies
kubectl rollout status deployment/aussie
kubectl get pods -l app.kubernetes.io/name=aussie
```

The repository also includes an opt-in disposable qualification. It creates a
four-worker kind cluster with one spare schedulable zone, forms a three-member
Cassandra cluster, provisions Redis, runs the reviewed migration path once to
bootstrap the operator-created keyspace, reapplies the steady-state base with
migrations disabled, and then waits for production readiness. It performs a
rolling gateway restart, scales Redis down and pauses the Cassandra processes
in turn to prove gateway readiness fails closed, restores both dependencies,
and removes one gateway pod while readiness traffic runs:

```sh
AUSSIE_QUALIFICATION_IMAGE=ghcr.io/mwistrand/aussie@sha256:<64-hex-digest> \
  make deployment-qualification
```

The qualification requires `cosign`, Docker, kind, kubectl, and openssl. Before
creating the cluster it verifies that the supplied digest was signed by this
repository's tag-based release workflow, pulls it with the runner's Docker
credentials, and loads it into the kind nodes. This is required because kind
nodes use their own containerd and cannot use the runner's Docker login
directly. For a local private image, authenticate Docker to its registry before
running the qualification.

Each run writes a Markdown report to
`api/build/qualification/kubernetes-qualification-<UTC timestamp>-<pid>.md`. Set
`AUSSIE_QUALIFICATION_REPORT` to choose another path. The report records the
released image digest, source commit, production profile, dependency images,
load threshold/result, and recovery checks so qualification evidence stays
tied to the artifact that was tested.

Before the dependency fault checks, the harness registers a disposable public
HTTP service through the real control-plane API and runs a 30-second open-loop
profile at 10 requests per second. It cycles through `/q/health/ready`,
`/q/health/live`, `/q/metrics`, and `/qualification-upstream/health`; every
request must succeed and the default p95 for each endpoint must remain at or
below one second. The report also records JVM heap/direct-memory and GC,
process/system, Vert.x event-loop, and application bulkhead metrics available
from `/q/metrics` after the load. Before collecting those results, the harness
waits until the routed endpoint succeeds directly on all three gateway pods,
so an accepted mutation with pending propagation cannot create false load
failures. Tune the evidence run explicitly
with `AUSSIE_QUALIFICATION_LOAD_SECONDS`,
`AUSSIE_QUALIFICATION_LOAD_INTERVAL_SECONDS`, and
`AUSSIE_QUALIFICATION_LOAD_P95_MAX_SECONDS`; the output reports per-endpoint
request totals, errors, and p50/p95/p99 latency. Redis and Cassandra recovery
durations are recorded from dependency loss through restored gateway readiness.
The default recovery bound is 120 seconds for Redis, Cassandra, and gateway
pod loss; set `AUSSIE_QUALIFICATION_RECOVERY_MAX_SECONDS` to change it. A run
fails when any measured recovery exceeds that bound.
The same registered service also exposes a disposable WebSocket endpoint. The
harness performs a production-profile WebSocket upgrade through the gateway
before the load, then pins a second long-lived connection to one gateway pod,
deletes that pod, and requires the graceful WebSocket close (code `1001`) within
40 seconds. Both results are recorded in the report.

Set `AUSSIE_QUALIFICATION_KEEP_CLUSTER=true` to retain the cluster for
inspection after a run. This is a release qualification check, not a substitute
for a provider-specific Cassandra backup/restore or network-policy test.

The WebSocket qualification opens 32 concurrent long-lived connections through
the routed service, holds them for 30 seconds, and requires every connection to
open and close cleanly. Set `AUSSIE_QUALIFICATION_WEBSOCKET_CONNECTIONS` and
`AUSSIE_QUALIFICATION_WEBSOCKET_HOLD_SECONDS` to change those bounds; the
values are recorded in the qualification report.

The Deployment uses three replicas, a PDB, topology-independent rolling update
limits, non-root/read-only containers, bounded resources, and health probes.
Quarkus begins HTTP/WebSocket drain on SIGTERM and waits for the configured
30-second WebSocket drain inside the 45-second pod grace period. Test the
release-specific ingress, Cassandra/Redis failover, backup restore, and
previous-image rollback before treating the base as a production certification.
The disposable qualification cordons a node that hosts one gateway replica and
uses the eviction API to prove the PDB rejects a second gateway eviction while
the replacement is unready. Only gateway pods are selected for this drain, so
the Cassandra and Redis fixtures remain in place. A strict readiness monitor
runs through the rolling restart, rollback, and node-drain convergence and
requires at least one successful request with zero failures. The protected
hosted rollback workflow remains the evidence for switching between released
image versions.
