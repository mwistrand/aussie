# Known limitations

This page records limits that are intentional or still require deployment-specific
evidence. It is part of the launch-readiness contract; it does not certify a
deployment or replace the [qualification report](launch-readiness.md).

- The project remains experimental until a signed production image passes the
  release gates in [launch readiness](launch-readiness.md).
- Cassandra must be operator-provisioned with `NetworkTopologyStrategy`, a
  supported replication factor, and `LOCAL_QUORUM`. The gateway does not create
  production keyspaces or prove provider backup/restore objectives.
- Redis-backed sessions, PKCE state, caches, rate-limit buckets, and pending
  propagation state are not a substitute for Cassandra durability. Redis loss
  can require reauthentication and must meet the deployment's own recovery
  objective.
- Dynamic upstream egress cannot be expressed safely as one universal
  `NetworkPolicy`. Each deployment must add reviewed allow rules for its actual
  upstream networks.
- The bundled rate limiter currently implements one bucket algorithm. Additional
  algorithms should be added only when a real product requirement exists.
- The repository does not claim a universal capacity envelope. HTTP, WebSocket,
  DNS, datastore, telemetry, and backup/restore limits must be measured for the
  target artifact and infrastructure.
- The release qualification harness is disposable evidence. It does not replace
  managed-provider failover, backup/restore, ingress/TLS, or organization-specific
  disaster-recovery exercises.
