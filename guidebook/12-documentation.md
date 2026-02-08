# Chapter 12: Documentation as Engineering Artifact

Most documentation in most codebases exists in one of two states: absent, or a single README written at project inception that has drifted so far from reality that it is actively misleading. A senior engineer might write that README, add a brief "how to run" section, and consider the documentation task complete. This chapter argues for a fundamentally different approach: documentation that is structured by audience, prescriptive during failure, exhaustive in configuration, and co-located with the code it explains.

The Aussie API Gateway maintains two entirely separate documentation trees -- `docs/platform/` for operators and `docs/api/` for service team consumers -- along with extensive Javadoc that embeds architectural reasoning directly in the source. This is not accidental. Each of these practices solves a specific class of problem that a single README cannot.

---

## 1. Audience-Aware Documentation

The most common documentation failure is writing for a single imagined reader. In practice, infrastructure software has at least two distinct audiences with conflicting needs: the people who run it and the people who build on top of it.

Aussie separates these concerns physically. The `docs/platform/` directory begins:

```
# Aussie Platform Guide

This guide is for platform teams deploying and operating the Aussie API Gateway.
```

And `docs/api/` begins:

```
# Aussie Consumer Guide

This guide is for developers onboarding their applications to the Aussie API Gateway.
```

The first sentence of each document declares its audience. This is deliberate -- someone landing on the wrong document can immediately redirect themselves.

### Why the Same Feature Needs Different Documentation

Consider rate limiting. The platform team documentation in `docs/platform/rate-limiting.md` covers algorithm selection, platform-wide maximums, storage backends, monitoring metrics, alert configuration, and debug logging:

```
### Algorithm Selection

| Algorithm | Best For | Behavior |
|-----------|----------|----------|
| `BUCKET` | General use | Allows controlled bursts, smooth refill |
| `FIXED_WINDOW` | Strict limits | Hard cutoff at window boundary |
| `SLIDING_WINDOW` | Smooth limits | More even distribution |
```

```
### Setting the Platform Maximum

The platform maximum prevents service teams from accidentally configuring
overly permissive rate limits that could enable DoS attacks.
```

The platform team needs to know how to tune the algorithm, set ceilings, choose storage backends (in-memory vs. Redis), and diagnose misbehavior. They do not need example client retry code.

The consumer-facing documentation in `docs/api/rate-limiting.md` covers an entirely different set of concerns: how to configure per-service limits in your service registration JSON, what response headers to expect, how to handle 429 responses with retry logic, and how client identification works:

```json
{
  "serviceId": "my-service",
  "rateLimitConfig": {
    "requestsPerWindow": 100,
    "burstCapacity": 100,
    "endpoints": {
      "/api/expensive-operation": {
        "requestsPerWindow": 10,
        "burstCapacity": 5
      }
    }
  }
}
```

```typescript
async function fetchWithRetry(url: string, maxRetries = 3): Promise<Response> {
  for (let attempt = 0; attempt < maxRetries; attempt++) {
    const response = await fetch(url);
    if (response.status !== 429) {
      return response;
    }
    const retryAfter = parseInt(response.headers.get('Retry-After') || '1');
    await new Promise(resolve => setTimeout(resolve, retryAfter * 1000));
  }
  throw new Error('Max retries exceeded');
}
```

The consumer needs to know what to put in their registration JSON and how to handle throttling in their client code. They do not need to know about Lua scripts in Redis or Prometheus alert rules.

The same split appears with WebSocket support. Platform teams get `docs/platform/websocket-configuration.md` covering idle timeouts, maximum lifetimes, ping/pong heartbeat tuning, per-instance connection limits, and close code semantics. API teams get `docs/api/websocket-onboarding.md` covering how to add `"type": "WEBSOCKET"` to their service registration, how to extract forwarded JWT claims in their backend handler, and complete working examples in Node.js and browser JavaScript.

### What a Senior Might Do Instead

Write a single "Rate Limiting" page that mixes algorithm selection advice with client retry code. The result is a document that overwhelms consumers with infrastructure details they cannot act on, and buries the operational knobs that platform engineers need in the middle of client-side code samples. Neither audience reads the whole thing. Both miss the parts relevant to them.

### Trade-offs

Maintaining two documentation trees for the same feature doubles the documentation surface area. When the rate limit response format changes, you must update both `docs/platform/rate-limiting.md` and `docs/api/rate-limiting.md`. This is real overhead. The alternative -- a single confused document that neither audience trusts -- is worse.

---

## 2. Operational Runbooks

Documentation that only describes what a system does under normal conditions is table stakes. The differentiating value is documentation that tells you what to do when the system breaks.

The platform rate limiting guide includes a troubleshooting section structured around symptoms rather than components:

```
### Common Issues

**1. Services reporting 429s unexpectedly**
- Check if platform max is too low
- Review service-level configuration
- Verify client identification is correct

**2. Rate limits not enforcing**
- Verify `AUSSIE_RATE_LIMITING_ENABLED=true`
- Check Redis connectivity (if using distributed limiter)
- Verify rate limit provider is loaded correctly

**3. High Redis latency**
- Consider using in-memory limiter for non-critical services
- Check Redis cluster health
- Review Lua script execution time
```

The token revocation troubleshooting section follows the same pattern:

```
### Revoked Token Still Works

1. Check if revocation is enabled: `aussie.auth.revocation.enabled=true`
2. Verify pub/sub is working (check logs for subscription errors)
3. Force bloom filter rebuild: `POST /admin/tokens/bloom-filter/rebuild`
```

Each troubleshooting entry starts with the symptom an operator would observe ("Revoked Token Still Works"), then provides an ordered diagnostic path. Step 1 checks the obvious. Step 2 checks the likely. Step 3 provides the escape hatch.

### Debug Logging Commands

The rate limiting guide provides exact log category configuration for diagnosis:

```properties
quarkus.log.category."aussie.adapter.out.ratelimit".level=DEBUG
quarkus.log.category."aussie.system.filter.RateLimitFilter".level=DEBUG
```

This matters because during an incident, the person debugging may not be the person who wrote the code. They need to know the exact Quarkus log category string, not just "enable debug logging." The difference between `aussie.adapter.out.ratelimit` and `aussie.system.filter.RateLimitFilter` is the difference between seeing storage-layer operations and seeing filter-level request decisions. Both are useful for different symptoms.

### What a Senior Might Do Instead

Add a "Troubleshooting" header at the bottom of the document with a single bullet: "Check the logs." During an incident at 3 AM, this is not helpful.

### Trade-offs

Detailed troubleshooting sections become outdated when the system changes. A bloom filter rebuild endpoint that was renamed or moved will cause the runbook to send operators to a 404. This is still better than no runbook. Incorrect troubleshooting steps at least tell you the system changed; an empty troubleshooting section tells you nothing.

---

## 3. Failure Scenario Tables

One of the most effective documentation patterns in Aussie is the explicit failure scenario table. The token revocation documentation includes this:

```
### Failure Scenarios

| Scenario | Behavior |
|----------|----------|
| Store unavailable at startup | Empty filter used; **all tokens pass as not revoked** (security risk) |
| Store unavailable during periodic rebuild | Previous filter remains active; retried at next interval |
| Pub/sub connection lost | New revocations not enforced until next rebuild (up to `rebuild-interval`) |
| Out of memory during rebuild | Reduce `expected-insertions` or increase heap |
```

Every row in this table represents a conversation that would otherwise happen on a Slack thread during an outage. "What happens if Redis is down when Aussie starts?" is not a theoretical question -- it is something that will happen in production, and the answer has security implications. The table makes explicit that an empty bloom filter means all tokens pass as not revoked. It uses bold text to call out the security risk.

The resource impact table for bloom filter rebuilds follows the same philosophy:

```
### Resource Impact

| Resource | Impact |
|----------|--------|
| **Memory** | 2x bloom filter memory temporarily (old + new coexist) |
| **Network** | Full scan of revocation store |
| **CPU** | Hashing all entries into new filter (~10μs per entry) |
```

This table answers capacity planning questions before they become production incidents. If your bloom filter has 1 million entries, the CPU cost is approximately 10 seconds. If you have 10 million entries and a 2-second SLA on rebuild, you know you have a problem before deploying.

### What a Senior Might Do Instead

Document the happy path ("bloom filter rebuilds every hour") and omit failure scenarios. The implicit assumption is that operators will reason about failure modes from the implementation. They will not, because they are reading documentation precisely because they do not have time to read the implementation.

### Trade-offs

Writing failure scenario tables forces the author to think through every failure mode during the documentation process. This is a feature, not a cost. If you cannot fill out the "Behavior" column for a scenario, you have found a gap in your design. The table doubles as a design review artifact.

---

## 4. Configuration Reference Tables

Aussie's platform guide ends with a comprehensive environment variables reference, organized by category. The token revocation section alone includes 12 variables:

```
### Token Revocation

| Variable | Default | Description |
|----------|---------|-------------|
| `AUSSIE_AUTH_REVOCATION_ENABLED` | `true` | Enable token revocation checks |
| `AUSSIE_AUTH_REVOCATION_CHECK_USER_REVOCATION` | `true` | Enable user-level revocation |
| `AUSSIE_AUTH_REVOCATION_CHECK_THRESHOLD` | `PT30S` | Skip check for tokens expiring within this threshold |
| `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_ENABLED` | `true` | Enable bloom filter optimization |
| `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_EXPECTED_INSERTIONS` | `100000` | Expected number of revoked tokens |
| `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_FALSE_POSITIVE_PROBABILITY` | `0.001` | Bloom filter false positive rate |
| `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_REBUILD_INTERVAL` | `PT1H` | Bloom filter rebuild interval |
| `AUSSIE_AUTH_REVOCATION_CACHE_ENABLED` | `true` | Enable local revocation cache |
| `AUSSIE_AUTH_REVOCATION_CACHE_MAX_SIZE` | `10000` | Maximum cache entries |
| `AUSSIE_AUTH_REVOCATION_CACHE_TTL` | `PT5M` | Cache entry TTL |
| `AUSSIE_AUTH_REVOCATION_PUBSUB_ENABLED` | `true` | Enable pub/sub for multi-instance sync |
| `AUSSIE_AUTH_REVOCATION_PUBSUB_CHANNEL` | `aussie:revocation:events` | Redis pub/sub channel name |
```

Every variable includes the exact environment variable name, its default value, and a description. The format column (when present, as in `production-secrets.md`) specifies the expected data type:

```
## Secrets Inventory

| Secret | Env Variable | Format | Purpose | Rotation Cadence |
|--------|-------------|--------|---------|------------------|
| JWS Signing Key | `AUSSIE_JWS_SIGNING_KEY` | RSA PKCS#8 PEM | Signs session JWS tokens | Quarterly |
| Bootstrap Key | `AUSSIE_BOOTSTRAP_KEY` | String (min 32 chars) | First-time admin setup | Single-use |
| Encryption Key | `AUTH_ENCRYPTION_KEY` | Base64-encoded 256-bit | Encrypts API key records at rest | Annually |
```

The secrets inventory adds a "Rotation Cadence" column. This is the kind of detail that, if absent, generates a support ticket every quarter: "How often should we rotate the signing key?"

### Why Exhaustive Config Docs Prevent Support Tickets

Every undocumented configuration option is a future Slack message. "What's the default for `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_EXPECTED_INSERTIONS`?" is a question that takes an engineer 30 seconds to answer but interrupts their flow for 15 minutes. Multiply by the number of platform teams deploying Aussie and the number of configuration variables, and undocumented defaults become a material cost.

The resiliency configuration section takes this further by documenting not just each variable but the behavior when a timeout fires:

```
**Timeout Behavior by Operation:**
- **HTTP Proxy**: Returns 504 Gateway Timeout if upstream doesn't respond
- **JWKS Fetch**: Falls back to cached keys if available on timeout
- **Session Operations**: Propagate error (critical operations)
- **Cache Reads**: Treat timeout as cache miss
- **Rate Limiting**: Fail-open (allow request) on timeout
- **Token Revocation**: Fail-closed (deny request) on timeout for security
```

Note the contrast between rate limiting (fail-open) and token revocation (fail-closed). This is a security design decision. Documenting it means operators understand the implications when Redis goes down: requests will still flow (rate limits may not enforce), but revoked tokens will be correctly rejected (potentially causing false denials). That trade-off is intentional, and the documentation makes it visible.

### What a Senior Might Do Instead

Document the five most important configuration variables and say "see application.properties for the rest." This forces operators to read Java `@ConfigMapping` interfaces to discover available knobs, which requires them to have the source code, understand Quarkus configuration mapping, and know which interface to look at.

### Trade-offs

Exhaustive configuration tables must be kept in sync with the actual configuration interfaces. When a new config property is added, the documentation must be updated. This is an ongoing maintenance cost. The alternative -- incomplete documentation that sends people to source code -- is faster to maintain because you maintain nothing, but it transfers the cost to every consumer of the system.

---

## 5. Architecture Decisions in Javadoc

External Architecture Decision Records (ADRs) have a well-known failure mode: they drift from the code. An ADR says "we chose strategy X for reason Y," the code evolves to strategy Z, and the ADR is never updated. Aussie's approach is to embed architectural reasoning directly in Javadoc, co-located with the code it describes.

The `RateLimitFilter` class comment explains not just what the filter does but why it is positioned where it is:

```java
/**
 * Reactive filter that enforces rate limits on incoming requests.
 *
 * <p>
 * This filter runs early in the request chain (before authentication) to
 * reject excessive traffic before incurring authentication overhead.
 *
 * <p>
 * Rate limits are resolved by looking up the {@link ServiceRegistration} via
 * {@link ServiceRegistry#getService(String)} and finding the matching route.
 * When a route match exists, service and endpoint-specific limits apply.
 * Otherwise, platform defaults are used.
 *
 * <p>
 * Client identification priority:
 * <ol>
 * <li>Session ID from cookie or header</li>
 * <li>Authorization header (bearer token hash)</li>
 * <li>API key ID header</li>
 * <li>Client IP from Forwarded or X-Forwarded-For or remote address</li>
 * </ol>
 */
```

The phrase "before authentication" is a design decision. Rate limiting could run after authentication, which would allow per-user limits but would also mean that every abusive request incurs the full cost of token validation. The Javadoc explains the trade-off: we reject excessive traffic *before* incurring authentication overhead.

The client identification priority list is equally significant. It documents a fallback chain where the most specific identifier (session ID) takes precedence over the least specific (IP address). This is a design decision that affects whether users behind a NAT share a rate limit bucket. It belongs in the code because it will change when the code changes.

The `ProxyRequestPreparer` embeds RFC references directly:

```java
/**
 * Prepare proxy requests by applying header filtering and forwarding rules.
 * This encapsulates the business logic for:
 * - Filtering hop-by-hop headers (RFC 2616 Section 13.5.1)
 * - Setting the Host header for the target
 * - Adding forwarding headers (X-Forwarded-* or RFC 7239 Forwarded)
 */
```

```java
/**
 * HTTP hop-by-hop headers that must not be forwarded to the upstream server.
 * These are connection-specific headers per RFC 2616 Section 13.5.1.
 */
private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade");
```

The constant `HOP_BY_HOP_HEADERS` is not self-explanatory without the RFC reference. Someone reading this code a year from now will ask "why is `upgrade` in this list?" The answer is RFC 2616 Section 13.5.1, and it is right there in the comment.

The `TokenRevocationService` Javadoc embeds performance targets:

```java
/**
 * Service for token revocation operations.
 *
 * <p>Implements a tiered caching strategy for high-performance revocation checks:
 * <ol>
 *   <li><b>TTL shortcut</b> - Skip check for tokens expiring within threshold</li>
 *   <li><b>Bloom filter</b> - O(1) "definitely not revoked" check (~100ns)</li>
 *   <li><b>Local cache</b> - LRU cache for confirmed revocations (~1us)</li>
 *   <li><b>Remote store</b> - Authoritative source via SPI (~1-5ms)</li>
 * </ol>
 *
 * <p>Performance targets:
 * <ul>
 *   <li>P50: &lt;100us (bloom filter hit)</li>
 *   <li>P99: &lt;500us (local cache hit)</li>
 *   <li>P99.9: &lt;5ms (remote lookup)</li>
 * </ul>
 */
```

This is not just documentation -- it is a contract. If someone modifies the tiered lookup and the P99 goes from 500 microseconds to 50 milliseconds, the Javadoc tells them they have violated the intended performance budget. The performance targets in the Javadoc and the performance targets in `docs/platform/token-revocation.md` are deliberately the same, establishing a consistency check between code and documentation.

The `RateLimitFilter` also contains an inline design note on OpenTelemetry span status:

```java
// Use OK status - rate limiting is expected behavior, not an error
// Errors would trigger alerts; rate limits are informational
span.setStatus(StatusCode.OK, "Rate limit exceeded");
```

This comment explains why a 429 response is recorded as `StatusCode.OK` instead of `StatusCode.ERROR` in the span. Without it, a future engineer would "fix" this to use `StatusCode.ERROR`, which would trigger false-positive alerts in every monitoring system.

### What a Senior Might Do Instead

Write Javadoc that restates the method name: "Rate limits incoming requests" on a method called `filterRequest`. Or worse, no Javadoc at all, with the reasoning living in a Confluence page that was last updated eight months ago.

### Trade-offs

Javadoc has a discoverability problem. An operator troubleshooting a production issue is unlikely to read Javadoc. That is why Aussie also has external documentation. The Javadoc serves a different audience: the engineer modifying the code, who needs to understand the reasoning behind the current design before changing it.

---

## 6. CLI Command Reference

Operational documentation must include the exact commands for common tasks. During an incident, nobody wants to discover the CLI's `--help` output for the first time. The token revocation documentation provides copy-pasteable commands:

```bash
# Revoke a specific token
aussie auth revoke token <jti>

# Revoke all tokens for a user
aussie auth revoke user <user-id>

# Check token revocation status
aussie auth revoke check <jti>

# List recent revocations
aussie auth revoke list --limit 50

# Force rebuild bloom filter
aussie auth revoke rebuild-filter
```

The bloom filter rebuild section goes further with a multi-instance Kubernetes command:

```bash
INSTANCES=$(kubectl get pods -l app=aussie -o jsonpath='{.items[*].status.podIP}')
for IP in $INSTANCES; do
  curl -X POST "http://${IP}:8080/admin/tokens/bloom-filter/rebuild"
done
```

This is not generic advice ("rebuild the bloom filter on each instance"). It is the exact shell loop you paste into your terminal during a pub/sub outage. It uses `kubectl` with the correct label selector and `jsonpath` expression.

The platform guide also documents API key management commands with flag tables:

```
| Flag | Short | Default | Description |
|------|-------|---------|-------------|
| `--name` | `-n` | (required) | Name for the API key |
| `--description` | `-d` | | Description of the key's purpose |
| `--ttl` | `-t` | 0 | TTL in days (0 = no expiration) |
| `--permissions` | `-p` | `*` | Permissions (comma-separated) |
```

### Why CLI Docs Matter for Incident Response

During a credential compromise, the response timeline is:

1. Identify the compromised token (need: `aussie auth revoke check <jti>`)
2. Revoke the token (need: `aussie auth revoke token <jti>`)
3. Revoke all tokens for the affected user (need: `aussie auth revoke user <user-id>`)
4. Verify propagation (need: `POST /admin/tokens/bloom-filter/rebuild` across instances)

Each of these steps has an exact command. The documentation orders them in the sequence you would actually execute them. An operator following this sequence does not need to understand bloom filters or pub/sub -- they need to know which commands to run and in what order.

### What a Senior Might Do Instead

Point operators to `aussie --help` and trust that the CLI's built-in documentation is sufficient. Built-in help is sufficient for discovery; it is insufficient for incident response, where you need curated sequences of commands, not alphabetical lists of flags.

### Trade-offs

CLI commands change. When `aussie auth revoke token` is renamed to `aussie token revoke`, every documentation reference breaks. This is a real maintenance burden. The alternative is that operators discover command changes during an incident, which is worse.

---

## 7. PromQL Examples for Dashboards

The observability guide in `docs/platform/observability.md` does not stop at listing available metrics. It provides exact PromQL queries for common monitoring needs:

```promql
# Requests by team
sum(rate(aussie_attributed_requests_total[5m])) by (team_id)

# Data transfer by tenant
sum(rate(aussie_attributed_bytes_ingress[5m]) + rate(aussie_attributed_bytes_egress[5m])) by (tenant_id)

# Compute units by service
sum(rate(aussie_attributed_compute_units[5m])) by (service_id)
```

It also provides complete Prometheus alerting rules:

```yaml
- alert: HighErrorRate
  expr: |
    sum(rate(aussie_errors_total[5m])) by (service_id)
    / sum(rate(aussie_requests_total[5m])) by (service_id) > 0.05
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "High error rate for {{ $labels.service_id }}"

- alert: AuthFailureSpike
  expr: |
    sum(rate(aussie_auth_failures_total[5m])) > 10
  for: 2m
  labels:
    severity: critical

- alert: CassandraPoolNearCapacity
  expr: |
    cassandra_pool_open_connections / on() aussie_bulkhead_cassandra_pool_max > 0.85
  for: 5m
  labels:
    severity: warning
```

The `CassandraPoolNearCapacity` alert is particularly instructive. It divides the actual pool usage metric from the Cassandra driver (`cassandra_pool_open_connections`) by the configured maximum from Aussie's bulkhead metrics (`aussie_bulkhead_cassandra_pool_max`), using PromQL's `on()` clause for cross-metric division. Writing this query from scratch requires understanding both the Cassandra driver's metric naming conventions and Aussie's custom gauge names. The documentation eliminates that discovery cost.

The metrics reference tables list every metric with its type, labels, and description:

```
| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `aussie.requests.total` | Counter | `service_id`, `method`, `status`, `status_class` | Total gateway requests |
| `aussie.proxy.latency` | Timer | `service_id`, `method`, `status_class` | Upstream proxy latency |
| `aussie.traffic.bytes` | Counter | `service_id`, `team_id`, `direction` | Traffic volume in bytes |
```

### What a Senior Might Do Instead

Document the metric names and leave PromQL as an exercise for the reader. This saves perhaps 20 minutes of documentation time and costs every platform team deploying Aussie the time to write (and debug) their own queries.

### Trade-offs

PromQL examples create implicit version coupling. If a metric is renamed from `aussie.requests.total` to `aussie.http.requests.total`, every documented query breaks. The documentation should be updated as part of the metric rename, but in practice this is easy to forget. Labels are especially fragile -- adding or removing a label can change query behavior silently.

---

## 8. SPI Implementation Checklists

When your system has extension points, the documentation must bridge the gap between interface definition and correct implementation. The token revocation SPI documentation in `docs/platform/token-revocation-spi.md` provides a structured implementation path.

It starts with the complete interface:

```java
public interface TokenRevocationRepository {
    Uni<Void> revoke(String jti, Instant expiresAt);
    Uni<Boolean> isRevoked(String jti);
    Uni<Void> revokeAllForUser(String userId, Instant issuedBefore, Instant expiresAt);
    Uni<Boolean> isUserRevoked(String userId, Instant issuedAt);
    Multi<String> streamAllRevokedJtis();
    Multi<String> streamAllRevokedUsers();
}
```

Then it specifies implementation requirements, each with code examples for multiple backends. For TTL-based expiration:

```java
// Redis example - uses native TTL
public Uni<Void> revoke(String jti, Instant expiresAt) {
    var ttlSeconds = Duration.between(Instant.now(), expiresAt).getSeconds();
    if (ttlSeconds <= 0) {
        return Uni.createFrom().voidItem(); // Already expired
    }
    return redis.setex(key, ttlSeconds, "1");
}

// Database example - scheduled cleanup
@Scheduled(every = "5m")
void cleanupExpired() {
    dataSource.execute("DELETE FROM revoked_tokens WHERE expires_at < NOW()");
}
```

The documentation explicitly addresses the fail-open vs. fail-closed decision:

```java
// Fail-Open: Token validation succeeds if revocation check fails
public Uni<Boolean> isRevoked(String jti) {
    return redis.exists(key)
        .onFailure().recoverWithItem(false); // Fail-open
}

// Fail-Closed: Token validation fails if revocation check fails
public Uni<Boolean> isRevoked(String jti) {
    return redis.exists(key)
        .onFailure().transform(e ->
            new RevocationCheckException("Cannot verify revocation", e));
}
```

The contract test coverage table tells implementers exactly what the test suite validates:

```
| Test | Description |
|------|-------------|
| `revoke() then isRevoked()` | Basic revocation works |
| `isRevoked() for unknown JTI` | Returns false for unknown tokens |
| `revokeAllForUser()` affects old tokens | Tokens before cutoff are revoked |
| `revokeAllForUser()` doesn't affect new tokens | Tokens after cutoff are valid |
| `streamAllRevokedJtis()` | Returns all revoked JTIs |
| `JTI/user revocation isolation` | Revocation types don't interfere |
```

The checklist itself is explicit:

```
### Implementation Checklist

- [ ] Implement all six interface methods
- [ ] Ensure TTL-based automatic expiration of revocation entries
- [ ] Handle connection failures gracefully (decide fail-open vs fail-closed)
- [ ] Add appropriate logging and metrics
- [ ] Test with `TokenRevocationRepositoryContractTest`
```

And a complete Memcached example demonstrates how to handle backends that lack certain capabilities (key iteration for bloom filter rebuild):

```java
@Override
public Multi<String> streamAllRevokedJtis() {
    // Memcached doesn't support key iteration
    // Option 1: Maintain a separate set of keys
    // Option 2: Return empty (bloom filter won't be populated from store)
    return Multi.createFrom().empty();
}
```

This is honest documentation. It does not pretend every backend can implement every method perfectly. It shows the workaround and explains the consequence.

### What a Senior Might Do Instead

Publish the interface Javadoc and expect implementers to figure out the semantics. This leads to implementations that technically compile but violate implicit contracts -- for example, an implementation where `streamAllRevokedJtis()` loads all revoked JTIs into memory at once rather than streaming them, causing OOM during bloom filter rebuild with large revocation sets.

### Trade-offs

Detailed SPI documentation creates a maintenance obligation: when the interface changes, the documentation, examples, contract test table, and checklist all need updating. This is significant overhead for interface evolution. However, SPI interfaces should be stable -- that is the point of having an SPI. The documentation cost incentivizes stability, which is itself a feature.

---

## 9. Security Consideration Documentation

Security configuration has a unique documentation requirement: the consequences of misconfiguration are severe and often invisible. The `docs/platform/production-secrets.md` document addresses this by being exhaustive and explicit about what must be protected.

The secrets inventory is not a paragraph of prose. It is a table:

```
| Secret | Env Variable | Format | Purpose | Rotation Cadence |
|--------|-------------|--------|---------|------------------|
| JWS Signing Key | `AUSSIE_JWS_SIGNING_KEY` | RSA PKCS#8 PEM | Signs session JWS tokens | Quarterly |
| Bootstrap Key | `AUSSIE_BOOTSTRAP_KEY` | String (min 32 chars) | First-time admin setup | Single-use |
| Encryption Key | `AUTH_ENCRYPTION_KEY` | Base64-encoded 256-bit | Encrypts API key records at rest | Annually |
| Cassandra Credentials | `CASSANDRA_USERNAME`, `CASSANDRA_PASSWORD` | String | Database authentication | Per org policy |
| Redis Password | `REDIS_PASSWORD` | String | Redis authentication | Per org policy |
| OIDC Client Secret | `OIDC_CLIENT_SECRET` | String | OAuth2 client authentication | Per IdP policy |
```

Every secret has a name, environment variable, expected format, purpose, and rotation cadence. The "Rotation Cadence" column is the difference between a secure deployment and one where the signing key has not been rotated since initial setup.

The document then provides key generation commands:

```bash
# RSA signing key (2048-bit PKCS#8)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out aussie-private.pem

# AES-256 encryption key
openssl rand -base64 32

# Bootstrap key
openssl rand -base64 32
```

And deployment-specific guidance for Kubernetes, HashiCorp Vault, AWS Secrets Manager, and CI/CD pipelines. Each with copy-pasteable examples.

The bootstrap mode documentation in the platform guide includes explicit security warnings in table format:

```
### Security Considerations

| Practice | Description |
|----------|-------------|
| **Use a strong key** | Minimum 32 characters, randomly generated |
| **Short-lived keys** | Bootstrap keys expire in <=24 hours by design |
| **Immediate rotation** | Create a permanent key and disable bootstrap immediately |
| **Audit logs** | All bootstrap operations are logged to `aussie.audit.bootstrap` |
| **Recovery mode caution** | Only use when absolutely necessary |
```

The `.env` file security section is blunt:

```
- The `.env` file is listed in `.gitignore` and must never be committed with real secrets
- It exists solely for local `./gradlew quarkusDev` convenience
- `AUSSIE_AUTH_DANGEROUS_NOOP` must always be `false` in production
- `AUSSIE_BOOTSTRAP_ENABLED` should be `false` after initial setup
```

The platform guide reinforces this with a production safeguard note:

```
> **Production safeguard:** If `AUSSIE_AUTH_DANGEROUS_NOOP=true` is set in production mode,
> the application will refuse to start with an `IllegalStateException`.
```

This is defense in depth through documentation: the code prevents the misconfiguration, and the documentation explains why. An operator who sees the `IllegalStateException` knows from the documentation that this is not a bug -- it is a safeguard.

### What a Senior Might Do Instead

Add a line to the README: "Make sure to use strong secrets in production." This is technically correct and practically useless. It does not tell you which values are secrets, what format they should be in, how to generate them, or how often to rotate them.

### Trade-offs

Security documentation can become a liability if it is inaccurate. If the documentation says the signing key format is RSA PKCS#8 PEM but the code actually expects PKCS#1, an operator will spend hours debugging a key loading error. Security documentation must be verified against the implementation with the same rigor as the implementation itself.

---

## The Underlying Principle

The common thread across all of these practices is that documentation is not a narrative -- it is a reference. Narratives are read once and forgotten. References are consulted repeatedly during deployments, incidents, and onboarding.

Tables beat paragraphs because tables are scannable. Exact commands beat generic advice because exact commands are pasteable. Separate audience trees beat unified documents because each audience can find what they need without wading through irrelevant content.

The cost is real. Maintaining two documentation trees, keeping configuration tables in sync with code, updating PromQL examples when metrics change, and revising SPI checklists when interfaces evolve all require ongoing engineering effort. That effort is an investment, not overhead. Every hour spent on documentation prevents multiple hours of support questions, incident confusion, and misconfiguration.

Documentation written this way is not an afterthought. It is an engineering artifact with the same weight as a test suite: it codifies expectations, catches design gaps, and serves as a communication channel between the people who build the system and the people who depend on it.
