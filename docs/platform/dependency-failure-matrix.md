# Dependency failure matrix

This matrix is the operational contract for dependency loss. A dependency
failure must not turn a security decision into an implicit allow. Timeouts are
upper bounds for one operation; callers must keep any surrounding request or
startup deadline smaller than the remaining budget.

| Dependency | Required / optional | Required when | Deadline and retry | Failure behavior | Readiness / liveness | Recovery |
|---|---|---|---|---|---|---|
| Redis | Required for a selected Redis-backed provider; optional otherwise | A Redis-backed session, revocation, cache, pub/sub, or rate-limit provider is selected | `aussie.resiliency.redis.operation-timeout` (default `PT1S`); no unbounded retry | Sessions and revocation deny; cache reads act as misses; rate limiting uses explicit `DENY` (default) or a bounded local bucket | Readiness down when the configured required provider is unavailable; liveness stays up | Health polling observes recovery; Redis-backed operations resume without replaying a quota burst |
| Cassandra | Required for durable storage/migrations; optional only with an explicitly non-production memory provider | A durable storage provider or application migrations are selected | `aussie.resiliency.cassandra.query-timeout` (default `PT5S`); driver retry policy remains bounded by that timeout | Startup/migration fails closed; control-plane reads and writes return an error; no memory-backed substitution | Readiness down; liveness stays up to avoid restart loops | A new startup or health cycle reconnects; failed migrations are reclaimed only through the migration lease |
| IdP / token endpoint | Required for enabled OIDC or remote token exchange; optional for locally signed-only deployments | OIDC login or token exchange is enabled | OIDC/token-exchange configured timeout (default depends on the enabled provider); no caller-controlled retry loop | Return an authentication/dependency error; never issue a session or accept an unverified token | Existing local routes remain governed by their configured policy; liveness stays up | Retry on a later request within the configured deadline; session creation succeeds only after validation |
| DNS / egress resolver | Required for every hostname-based egress; optional only for routes using already validated literal addresses | An upstream, JWKS, or token endpoint needs a hostname | DNS lookup is bounded to 5 seconds; resolve every address before connecting | Deny the egress operation when lookup fails, times out, or returns a blocked address | Does not change process liveness; JWKS remains unavailable unless a permitted fresh/stale cache entry exists | Resolve again on the next bounded operation; no unpinned fallback address is used |
| Upstream service | Required per routed request; optional when no route targets it | A routed HTTP or WebSocket request needs the service | Shared HTTP connect/request limits; service timeout cannot exceed the configured global maximum | Return a bounded 502/timeout response; WebSocket admission fails before client upgrade | Gateway liveness remains independent of upstream health | New requests may succeed after the upstream recovers; existing failed exchanges are not replayed |
| Telemetry | Optional to request correctness; required by production operations policy | Metrics/tracing/security handlers are configured | Security-event dispatch uses its bounded queue and shutdown drain timeout | Drop on saturation, record bounded drop telemetry, and never run handlers on request/event-loop threads | Telemetry loss does not make the data plane report unhealthy; dispatcher shutdown is bounded | Handler processing resumes after pressure; forced shutdown drops only work that missed the drain budget |
| Signing / JWKS | Required for protected JWT validation or gateway token issuance; optional for anonymous-only operation | A protected route validates external JWTs or signs gateway tokens | JWKS fetch timeout from `aussie.resiliency.jwks.fetch-timeout`; use only configured maximum-stale cache | Cache miss or expired stale data fails authentication/signing; no empty-key fallback | Signing readiness stays down without usable authority; liveness stays up | Refresh on the next bounded fetch; a valid cached key set is reused only inside its explicit stale window |
| Migration state | Required when migrations are enabled; optional only when schema ownership is external and explicitly disabled | Cassandra migrations are enabled or a schema state is incomplete | Total migration deadline `aussie.resiliency.cassandra.migration-timeout` (default `PT30S`); each query is bounded by `aussie.resiliency.cassandra.query-timeout`; ownership is held by the five-minute LWT lease | Startup/readiness fails on a failed, unknown, or checksum-mismatched migration | Readiness down; liveness stays up | A new owner may reclaim an expired lease; repair uses an idempotent forward-fix, not an automatic down migration |

Operational rules:

- Readiness describes whether required invariants can be enforced; liveness only
  describes whether the process and event loops are progressing.
- Configure fallback behavior explicitly. `ALLOW` is a legacy development mode,
  not a production outage strategy.
- Recovery must be gradual and bounded. Do not add retries around a dependency
  that already has a deadline without accounting for the shared request budget.

The matrix is reviewed with plans 07 and 09 and should be attached to the
release evidence whenever a dependency policy or timeout changes.
