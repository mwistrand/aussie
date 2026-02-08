# Token Revocation

This document describes the token revocation system in Aussie, including architecture, configuration, and SPI implementation guidelines.

## Overview

Aussie supports immediate token revocation for security-critical operations like:
- User logout (invalidate current session)
- Logout everywhere (invalidate all user sessions)
- Compromised credential response
- Administrative token revocation

## Architecture

Token revocation uses a tiered caching strategy for high-performance lookups:

```
Request
   │
   ▼
┌─────────────────────────────────────────────────────────┐
│ Tier 0: TTL Shortcut                                    │
│ Skip check for tokens expiring within threshold (30s)  │
└───────────────────────────┬─────────────────────────────┘
                            │ Token has sufficient TTL
                            ▼
┌─────────────────────────────────────────────────────────┐
│ Tier 1: Bloom Filter (~100ns)                           │
│ "Definitely not revoked" check - no network I/O        │
└───────────────────────────┬─────────────────────────────┘
                            │ Maybe revoked (positive)
                            ▼
┌─────────────────────────────────────────────────────────┐
│ Tier 2: Local Cache (~1μs)                              │
│ LRU cache for confirmed revocations                    │
└───────────────────────────┬─────────────────────────────┘
                            │ Not in cache
                            ▼
┌─────────────────────────────────────────────────────────┐
│ Tier 3: Remote Store (~1-5ms)                           │
│ Redis/SPI backend - authoritative source               │
└─────────────────────────────────────────────────────────┘
```

### Performance Targets

| Metric | Target |
|--------|--------|
| P50 latency | < 100μs (bloom filter hit) |
| P99 latency | < 500μs (local cache hit) |
| P99.9 latency | < 5ms (remote lookup) |

## Configuration

```properties
# Enable/disable token revocation
aussie.auth.revocation.enabled=true

# Enable user-level revocation checks (logout-everywhere)
aussie.auth.revocation.check-user-revocation=true

# Skip check for tokens expiring within this threshold
aussie.auth.revocation.check-threshold=PT30S

# Bloom filter configuration
aussie.auth.revocation.bloom-filter.enabled=true
aussie.auth.revocation.bloom-filter.expected-insertions=100000
aussie.auth.revocation.bloom-filter.false-positive-probability=0.001
aussie.auth.revocation.bloom-filter.rebuild-interval=PT1H

# Local cache configuration
aussie.auth.revocation.cache.enabled=true
aussie.auth.revocation.cache.max-size=10000
aussie.auth.revocation.cache.ttl=PT5M

# Pub/sub for multi-instance synchronization
aussie.auth.revocation.pubsub.enabled=true
aussie.auth.revocation.pubsub.channel=aussie:revocation:events
```

### Bloom Filter Sizing

Size the bloom filter based on your expected revocation volume:

| Workload | Expected Insertions | Memory | False Positive Rate |
|----------|--------------------:|-------:|--------------------:|
| Small (<1K RPS) | 100,000 | ~1.2 MB | 0.1% |
| Medium (1-10K RPS) | 1,000,000 | ~12 MB | 0.1% |
| Large (>10K RPS) | 10,000,000 | ~120 MB | 0.1% |

## Admin API

### Revoke a Specific Token by JTI

```bash
DELETE /admin/tokens/{jti}
Content-Type: application/json

{"reason": "Compromised credential"}  # Optional body

# Response: 204 No Content
```

### Revoke a Token by Full JWT

```bash
POST /admin/tokens/revoke
Content-Type: application/json

{"token": "eyJhbGciOiJSUzI1...", "reason": "User request"}

# Response: 200 OK
{
  "jti": "abc123",
  "status": "revoked",
  "revokedAt": "2024-01-15T10:30:00Z"
}
```

### Revoke All Tokens for a User

```bash
DELETE /admin/tokens/users/{userId}
Content-Type: application/json

{"reason": "Logout everywhere"}  # Optional body

# Response: 204 No Content
```

### Check Token Status

```bash
GET /admin/tokens/{jti}/status

# Response: 200 OK
{
  "jti": "abc123",
  "revoked": true,
  "checkedAt": "2024-01-15T10:31:00Z"
}
```

### List Revoked Tokens

```bash
GET /admin/tokens?limit=50

# Response: 200 OK
{
  "revokedTokens": ["jti1", "jti2", ...],
  "count": 50,
  "limit": 50
}
```

### List Users with Revocations

```bash
GET /admin/tokens/users?limit=50

# Response: 200 OK
{
  "revokedUsers": ["user1", "user2", ...],
  "count": 10,
  "limit": 50
}
```

### Inspect Token Claims

Decode a JWT without validation to view its claims (useful for finding JTI):

```bash
POST /admin/tokens/inspect
Content-Type: application/json

{"token": "eyJhbGciOiJSUzI1..."}

# Response: 200 OK
{
  "jti": "abc123",
  "subject": "user-123",
  "issuer": "https://auth.example.com",
  "audience": ["api"],
  "issuedAt": "2024-01-15T09:00:00Z",
  "expiresAt": "2024-01-15T10:00:00Z",
  "otherClaims": {...}
}
```

### Rebuild Bloom Filter

```bash
POST /admin/tokens/bloom-filter/rebuild

# Response: 200 OK
{
  "status": "rebuilt",
  "rebuiltAt": "2024-01-15T10:32:00Z"
}
```

## CLI Commands

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

## SPI: TokenRevocationRepository

Platform teams can implement custom storage backends by implementing the `TokenRevocationRepository` interface.

### Interface

```java
public interface TokenRevocationRepository {

    /**
     * Revoke a specific token by JTI.
     */
    Uni<Void> revoke(String jti, Instant expiresAt);

    /**
     * Check if a JTI is revoked.
     */
    Uni<Boolean> isRevoked(String jti);

    /**
     * Revoke all tokens for a user issued before a timestamp.
     */
    Uni<Void> revokeAllForUser(String userId, Instant issuedBefore, Instant expiresAt);

    /**
     * Check if a user's tokens issued at a specific time are revoked.
     */
    Uni<Boolean> isUserRevoked(String userId, Instant issuedAt);

    /**
     * Stream all revoked JTIs (for bloom filter rebuild).
     */
    Multi<String> streamAllRevokedJtis();

    /**
     * Stream all users with blanket revocations.
     */
    Multi<String> streamAllRevokedUsers();
}
```

### Implementation Checklist

- [ ] Implement all six interface methods
- [ ] Ensure TTL-based automatic expiration of revocation entries
- [ ] Handle connection failures gracefully (decide fail-open vs fail-closed)
- [ ] Add appropriate logging and metrics
- [ ] Test with `TokenRevocationRepositoryContractTest`

### Contract Tests

Extend the abstract contract test to verify your implementation:

```java
class MyCustomTokenRevocationRepositoryContractTest
    extends TokenRevocationRepositoryContractTest {

    private MyCustomRepository repository;

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.shutdown();
        }
    }

    @Override
    protected TokenRevocationRepository createRepository() {
        repository = new MyCustomRepository(/* config */);
        return repository;
    }
}
```

### Reference Implementation: Redis

The Redis implementation (`RedisTokenRevocationRepository`) demonstrates:

1. **JTI revocation**: Stored as `aussie:revoked:jti:{jti}` with TTL
2. **User revocation**: Stored as `aussie:revoked:user:{userId}` with value = revocation timestamp
3. **Automatic expiration**: Uses Redis TTL for cleanup
4. **Streaming**: Uses SCAN for efficient iteration

```java
// Example: Revoke a JTI
public Uni<Void> revoke(String jti, Instant expiresAt) {
    var key = keyPrefix + jti;
    var ttlSeconds = Duration.between(Instant.now(), expiresAt).getSeconds();
    return redis.setex(key, ttlSeconds, "1");
}
```

## SPI: RevocationEventPublisher

For multi-instance deployments, implement pub/sub to keep bloom filters synchronized:

```java
public interface RevocationEventPublisher {

    /**
     * Publish a JTI revocation event to other instances.
     */
    Uni<Void> publishJtiRevoked(String jti, Instant expiresAt);

    /**
     * Publish a user revocation event to other instances.
     */
    Uni<Void> publishUserRevoked(String userId, Instant issuedBefore, Instant expiresAt);

    /**
     * Subscribe to revocation events from other instances.
     */
    Multi<RevocationEvent> subscribe();
}
```

## Service Team Guidelines

### Token Validation

Services should rely on Aussie's token validation rather than performing local-only JWT verification. Aussie's validation includes:

1. Signature verification
2. Expiration check
3. **Revocation check** (local-only verification misses this)

### Handling Revoked Tokens

When a token is revoked:
- Aussie validation returns `401 Unauthorized`
- The response includes `X-Token-Revoked: true` header
- Services should prompt re-authentication

### Emergency Revocation

In case of credential compromise:

1. Use admin API or CLI to revoke affected tokens
2. Bloom filter updates propagate within seconds via pub/sub
3. All Aussie instances will reject revoked tokens

## Troubleshooting

### Revoked Token Still Works

1. Check if revocation is enabled: `aussie.auth.revocation.enabled=true`
2. Verify pub/sub is working (check logs for subscription errors)
3. Force bloom filter rebuild: `POST /admin/tokens/bloom-filter/rebuild`

### High False Positive Rate

1. Check bloom filter sizing vs actual revocation count
2. Increase `expected-insertions` if undersized
3. Monitor cache hit rate via metrics

### Slow Revocation Checks

1. Verify Redis connectivity
2. Check bloom filter is enabled and initialized
3. Review cache configuration

## Bloom Filter Rebuild

The bloom filter is rebuilt periodically to maintain consistency with the authoritative store.

### Rebuild Behavior

1. **Startup**: Empty filters initialize immediately; full rebuild runs asynchronously
2. **Periodic**: Scheduled every `rebuild-interval` (default: 1 hour)
3. **Manual**: Triggered via `POST /admin/tokens/bloom-filter/rebuild`

Rebuilds stream all revoked JTIs and users from the remote store, construct new filters in memory, and atomically swap them in. The previous filter remains active during rebuild.

### Resource Impact

| Resource | Impact |
|----------|--------|
| **Memory** | 2x bloom filter memory temporarily (old + new coexist) |
| **Network** | Full scan of revocation store |
| **CPU** | Hashing all entries into new filter (~10μs per entry) |

### Multi-Instance Behavior

Each instance maintains its own independent bloom filter with independent rebuild schedules (staggered by startup time). New revocations propagate via pub/sub between rebuilds. With pub/sub enabled, `rebuild-interval` can be lengthened (e.g., 6 hours) since the periodic rebuild is primarily a consistency safeguard.

### Failure Scenarios

| Scenario | Behavior |
|----------|----------|
| Store unavailable at startup | Empty filter used; **all tokens pass as not revoked** (security risk) |
| Store unavailable during periodic rebuild | Previous filter remains active; retried at next interval |
| Pub/sub connection lost | New revocations not enforced until next rebuild (up to `rebuild-interval`) |
| Out of memory during rebuild | Reduce `expected-insertions` or increase heap |

### Capacity Planning

Set `expected-insertions` to `daily_revocations × max_token_ttl_days × safety_factor`. See [Bloom Filter Sizing](#bloom-filter-sizing) for memory estimates.

### Manual Rebuild

To rebuild across all instances (Kubernetes example):

```bash
INSTANCES=$(kubectl get pods -l app=aussie -o jsonpath='{.items[*].status.podIP}')
for IP in $INSTANCES; do
  curl -X POST "http://${IP}:8080/admin/tokens/bloom-filter/rebuild"
done
```

Trigger a manual rebuild after bulk revocations, backup restores, or pub/sub outages.
