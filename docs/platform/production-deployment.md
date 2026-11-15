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
in-cluster Cassandra/Redis only when their namespaces carry
`aussie.network/cassandra=true` and `aussie.network/redis=true`, respectively.
For managed Cassandra or Redis outside the cluster, use the
`deploy/kubernetes/overlays/external-dependencies` overlay, replacing its
documentation-only IP blocks with the provider endpoint CIDRs. A namespace
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
Redis, applies the rendered base, waits for production readiness, and removes
one gateway pod while readiness traffic runs:

```sh
AUSSIE_QUALIFICATION_IMAGE=ghcr.io/mwistrand/aussie@sha256:<64-hex-digest> \
  make deployment-qualification
```

Set `AUSSIE_QUALIFICATION_KEEP_CLUSTER=true` to retain the cluster for
inspection after a run. This is a release qualification check, not a substitute
for a provider-specific Cassandra backup/restore or network-policy test.

The Deployment uses three replicas, a PDB, topology-independent rolling update
limits, non-root/read-only containers, bounded resources, and health probes.
Quarkus begins HTTP/WebSocket drain on SIGTERM and waits for the configured
30-second WebSocket drain inside the 45-second pod grace period. Test the
release-specific ingress, Cassandra/Redis failover, backup restore, and rollback
before treating the base as a production certification.
