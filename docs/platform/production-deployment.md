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
three-worker kind cluster, forms a three-member Cassandra cluster, provisions
Redis, runs the reviewed migration path once to bootstrap the operator-created
keyspace, reapplies the steady-state base with migrations disabled, and then
waits for production readiness. It performs a rolling gateway restart, scales
Redis down and pauses the Cassandra processes in turn to prove gateway
readiness fails closed, restores both dependencies, and removes one gateway pod
while readiness traffic runs:

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

Before the dependency fault checks, the harness runs a 30-second open-loop
operational-endpoint profile at 10 requests per second. It cycles through
`/q/health/ready`, `/q/health/live`, and `/q/metrics`; every request must
succeed and the default p95 for each endpoint must remain at or below one
second. The report also records JVM heap/direct-memory and GC, process/system,
Vert.x event-loop, and application bulkhead metrics available from `/q/metrics`
after the load. Tune the evidence run explicitly
with `AUSSIE_QUALIFICATION_LOAD_SECONDS`,
`AUSSIE_QUALIFICATION_LOAD_INTERVAL_SECONDS`, and
`AUSSIE_QUALIFICATION_LOAD_P95_MAX_SECONDS`; the output reports per-endpoint
request totals, errors, and p50/p95/p99 latency. Redis and Cassandra recovery
durations are recorded from dependency loss through restored gateway readiness.

Set `AUSSIE_QUALIFICATION_KEEP_CLUSTER=true` to retain the cluster for
inspection after a run. This is a release qualification check, not a substitute
for a provider-specific Cassandra backup/restore or network-policy test.

The Deployment uses three replicas, a PDB, topology-independent rolling update
limits, non-root/read-only containers, bounded resources, and health probes.
Quarkus begins HTTP/WebSocket drain on SIGTERM and waits for the configured
30-second WebSocket drain inside the 45-second pod grace period. Test the
release-specific ingress, Cassandra/Redis failover, backup restore, and
previous-image rollback before treating the base as a production certification.
The disposable qualification also runs a Kubernetes rollback to the previous
ReplicaSet after its rolling restart and keeps strict readiness traffic running
through both transitions; the protected hosted rollback workflow remains the
evidence for switching between released image versions.
