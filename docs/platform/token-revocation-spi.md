# Token Revocation SPI

This document provides detailed guidance for platform teams implementing custom token revocation storage backends.

## Overview

The Token Revocation SPI allows platform teams to integrate Aussie with their existing infrastructure by implementing two interfaces:

1. `TokenRevocationRepository` - Storage backend for revocation data
2. `RevocationEventPublisher` - Pub/sub for multi-instance synchronization

## TokenRevocationRepository Interface

```java
package aussie.core.port.out;

public interface TokenRevocationRepository {

    /**
     * Revoke a specific token by JTI.
     *
     * @param jti       the JWT ID to revoke
     * @param expiresAt when the revocation entry should expire (token's original expiry)
     * @return Uni completing when revocation is persisted
     */
    Uni<Void> revoke(String jti, Instant expiresAt);

    /**
     * Check if a JTI is revoked.
     *
     * @param jti the JWT ID to check
     * @return Uni with true if revoked, false otherwise
     */
    Uni<Boolean> isRevoked(String jti);

    /**
     * Revoke all tokens for a user issued before a timestamp.
     *
     * @param userId       the user ID
     * @param issuedBefore tokens issued before this time are revoked
     * @param expiresAt    when the revocation entry should expire
     * @return Uni completing when revocation is persisted
     */
    Uni<Void> revokeAllForUser(String userId, Instant issuedBefore, Instant expiresAt);

    /**
     * Check if a user's tokens issued at a specific time are revoked.
     *
     * @param userId   the user ID
     * @param issuedAt when the token was issued
     * @return Uni with true if tokens at this time are revoked
     */
    Uni<Boolean> isUserRevoked(String userId, Instant issuedAt);

    /**
     * Stream all revoked JTIs for bloom filter rebuild.
     *
     * @return Multi of all currently revoked JTIs
     */
    Multi<String> streamAllRevokedJtis();

    /**
     * Stream all users with blanket revocations.
     *
     * @return Multi of user IDs with active blanket revocations
     */
    Multi<String> streamAllRevokedUsers();
}
```

## Implementation Requirements

### 1. TTL-Based Expiration

Revocation entries must expire automatically. The `expiresAt` parameter indicates when the entry should be removed:

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
public Uni<Void> revoke(String jti, Instant expiresAt) {
    return dataSource.execute(
        "INSERT INTO revoked_tokens (jti, expires_at) VALUES (?, ?)",
        jti, expiresAt
    );
}

// Plus scheduled job to clean expired entries
@Scheduled(every = "5m")
void cleanupExpired() {
    dataSource.execute("DELETE FROM revoked_tokens WHERE expires_at < NOW()");
}
```

### 2. User Revocation Semantics

User-level revocation works on a timestamp basis:
- Store the `issuedBefore` timestamp with the user ID
- A token is revoked if `token.issuedAt < storedIssuedBefore`

```java
public Uni<Boolean> isUserRevoked(String userId, Instant issuedAt) {
    return redis.get(userKey(userId))
        .map(value -> {
            if (value == null) return false;
            var issuedBefore = Instant.parse(value);
            return issuedAt.isBefore(issuedBefore);
        });
}
```

### 3. Connection Failure Handling

Decide whether to fail-open or fail-closed on connection errors:

**Fail-Open (Default):** Token validation succeeds if revocation check fails
```java
public Uni<Boolean> isRevoked(String jti) {
    return redis.exists(key)
        .onFailure().recoverWithItem(false); // Fail-open
}
```

**Fail-Closed:** Token validation fails if revocation check fails
```java
public Uni<Boolean> isRevoked(String jti) {
    return redis.exists(key)
        .onFailure().transform(e ->
            new RevocationCheckException("Cannot verify revocation", e));
}
```

### 4. Streaming for Bloom Filter Rebuild

The `streamAllRevokedJtis()` and `streamAllRevokedUsers()` methods must be efficient for large datasets:

```java
// Redis - use SCAN instead of KEYS
public Multi<String> streamAllRevokedJtis() {
    return redis.scan(ScanArgs.Builder.matches(keyPrefix + "*"))
        .map(key -> key.substring(keyPrefix.length()));
}

// Database - use cursor-based pagination
public Multi<String> streamAllRevokedJtis() {
    return Multi.createFrom().emitter(emitter -> {
        String cursor = null;
        do {
            var batch = dataSource.query(
                "SELECT jti FROM revoked_tokens WHERE jti > ? LIMIT 1000",
                cursor
            );
            for (var row : batch) {
                emitter.emit(row.getString("jti"));
                cursor = row.getString("jti");
            }
        } while (!batch.isEmpty());
        emitter.complete();
    });
}
```

## RevocationEventPublisher Interface

```java
package aussie.core.port.out;

public interface RevocationEventPublisher {

    /**
     * Publish a revocation event to other instances.
     */
    Uni<Void> publish(RevocationEvent event);

    /**
     * Subscribe to revocation events from other instances.
     */
    Multi<RevocationEvent> subscribe();
}
```

### Event Types

```java
public sealed interface RevocationEvent {

    record JtiRevoked(String jti, Instant expiresAt) implements RevocationEvent {}

    record UserRevoked(String userId, Instant issuedBefore, Instant expiresAt)
        implements RevocationEvent {}
}
```

### Redis Implementation Example

```java
public class RedisRevocationEventPublisher implements RevocationEventPublisher {

    private final RedisAPI redis;
    private final String channel;
    private final ObjectMapper mapper;

    @Override
    public Uni<Void> publish(RevocationEvent event) {
        return Uni.createFrom()
            .item(() -> mapper.writeValueAsString(event))
            .flatMap(json -> redis.publish(channel, json))
            .replaceWithVoid();
    }

    @Override
    public Multi<RevocationEvent> subscribe() {
        return redis.subscribe(channel)
            .onItem().transform(msg -> mapper.readValue(msg, RevocationEvent.class));
    }
}
```

## Contract Tests

Use the provided contract test to verify your implementation:

```java
class MyCustomRepositoryContractTest extends TokenRevocationRepositoryContractTest {

    private MyCustomRepository repository;

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    @Override
    protected TokenRevocationRepository createRepository() {
        // Create fresh instance for each test
        repository = new MyCustomRepository(config);
        return repository;
    }
}
```

### Contract Test Coverage

The contract tests verify:

| Test | Description |
|------|-------------|
| `revoke() then isRevoked()` | Basic revocation works |
| `isRevoked() for unknown JTI` | Returns false for unknown tokens |
| `multiple revocations` | All revocations tracked |
| `revokeAllForUser()` affects old tokens | Tokens before cutoff are revoked |
| `revokeAllForUser()` doesn't affect new tokens | Tokens after cutoff are valid |
| `isUserRevoked() for unknown user` | Returns false for unknown users |
| `streamAllRevokedJtis()` | Returns all revoked JTIs |
| `streamAllRevokedUsers()` | Returns all users with revocations |
| `JTI/user revocation isolation` | Revocation types don't interfere |

## Registration

Register your implementation using CDI:

```java
@ApplicationScoped
@Alternative
@Priority(1)
public class MyCustomTokenRevocationRepository implements TokenRevocationRepository {
    // Implementation
}
```

Or configure via properties:

```properties
aussie.auth.revocation.repository=my-custom
```

## Metrics

Implementations should emit the following metrics:

| Metric | Type | Description |
|--------|------|-------------|
| `aussie_revocation_check_total` | Counter | Total revocation checks |
| `aussie_revocation_check_duration_seconds` | Histogram | Check latency |
| `aussie_revocation_store_error_total` | Counter | Storage errors |
| `aussie_revoked_tokens_total` | Gauge | Current revoked token count |

## Example: Memcached Implementation

```java
@ApplicationScoped
public class MemcachedTokenRevocationRepository implements TokenRevocationRepository {

    private final MemcachedClient client;
    private final String keyPrefix;

    public MemcachedTokenRevocationRepository(MemcachedClient client) {
        this.client = client;
        this.keyPrefix = "aussie:revoked:";
    }

    @Override
    public Uni<Void> revoke(String jti, Instant expiresAt) {
        return Uni.createFrom().item(() -> {
            int ttl = (int) Duration.between(Instant.now(), expiresAt).getSeconds();
            client.set(keyPrefix + "jti:" + jti, ttl, "1");
            return null;
        });
    }

    @Override
    public Uni<Boolean> isRevoked(String jti) {
        return Uni.createFrom().item(() ->
            client.get(keyPrefix + "jti:" + jti) != null
        );
    }

    @Override
    public Uni<Void> revokeAllForUser(String userId, Instant issuedBefore, Instant expiresAt) {
        return Uni.createFrom().item(() -> {
            int ttl = (int) Duration.between(Instant.now(), expiresAt).getSeconds();
            client.set(keyPrefix + "user:" + userId, ttl, issuedBefore.toString());
            return null;
        });
    }

    @Override
    public Uni<Boolean> isUserRevoked(String userId, Instant issuedAt) {
        return Uni.createFrom().item(() -> {
            var value = (String) client.get(keyPrefix + "user:" + userId);
            if (value == null) return false;
            return issuedAt.isBefore(Instant.parse(value));
        });
    }

    @Override
    public Multi<String> streamAllRevokedJtis() {
        // Memcached doesn't support key iteration
        // Option 1: Maintain a separate set of keys
        // Option 2: Return empty (bloom filter won't be populated from store)
        return Multi.createFrom().empty();
    }

    @Override
    public Multi<String> streamAllRevokedUsers() {
        return Multi.createFrom().empty();
    }
}
```
