# Chapter 10: SPI Design and Extension Points -- Building for Teams You'll Never Meet

The most telling sign of a system designed by senior engineers versus staff engineers is what happens when a team you have never spoken to tries to extend it. Senior engineers build systems that work. Staff engineers build systems that other people can build on.

This chapter examines how Aussie Gateway defines extension points through Service Provider Interfaces (SPIs) and port abstractions, enabling platform teams to swap storage backends, messaging systems, metrics providers, and authentication mechanisms without modifying a single line of core code. The discussion covers the specific interfaces, their reference implementations, the contract tests that make extension safe, and the bootstrap mechanism that solves a subtle chicken-and-egg problem.

---

## 10.1 The SPI Philosophy

An API gateway sits at the intersection of every team's infrastructure. The authentication team wants SAML. The platform team runs DynamoDB instead of Cassandra. The observability group uses Datadog instead of Prometheus. The security team needs revocation events in Kafka, not Redis pub/sub.

You will never meet most of these teams. You will certainly never be in the room when they decide to integrate. The only thing standing between them and a successful integration is the quality of your interfaces.

The architecture follows hexagonal (ports and adapters) principles with a specific discipline: **core defines the port interfaces; adapters implement them; the SPI package exposes provider interfaces for ServiceLoader discovery.**

```
core/port/out/          -- Port interfaces (what core needs)
spi/                    -- Provider interfaces (how extensions register)
adapter/out/            -- Reference implementations (what ships by default)
META-INF/services/      -- ServiceLoader registration files
```

The `core/port/out/` directory in Aussie contains 22 outbound port interfaces. Here is the full inventory:

```
api/src/main/java/aussie/core/port/out/
    ApiKeyRepository.java
    AuthKeyCache.java
    ConfigurationCache.java
    ForwardedHeaderBuilder.java
    ForwardedHeaderBuilderProvider.java
    JwksCache.java
    Metrics.java
    OidcRefreshTokenRepository.java
    PkceChallengeRepository.java
    ProxyClient.java
    RateLimiter.java
    RevocationEventPublisher.java
    RoleRepository.java
    SamplingConfigRepository.java
    SecurityMonitoring.java
    ServiceRegistrationRepository.java
    SessionRepository.java
    StorageHealthIndicator.java
    TrafficAttributing.java
    TranslationConfigCache.java
    TranslationConfigRepository.java
    TranslationMetrics.java
```

And the `spi/` package provides the discovery and registration layer:

```
api/src/main/java/aussie/spi/
    AuthenticationProvider.java
    AuthKeyCacheProvider.java
    AuthKeyStorageProvider.java
    ConfigurationCacheProvider.java
    FailedAttemptRepository.java
    OidcTokenExchangeProvider.java
    PkceStorageProvider.java
    RateLimiterProvider.java
    RoleStorageProvider.java
    SamplingConfigProvider.java
    SecurityEvent.java
    SecurityEventHandler.java
    SessionStorageProvider.java
    SigningKeyRepository.java
    StorageAdapterConfig.java
    StorageProviderException.java
    StorageRepositoryProvider.java
    TokenIssuerProvider.java
    TokenRevocationRepository.java
    TokenTranslatorProvider.java
    TokenValidatorProvider.java
    TranslationConfigCacheProvider.java
    TranslationConfigStorageProvider.java
```

**What a senior might do instead:** Define concrete classes with protected methods for override points, or use a plugin system with runtime class loading. Both approaches create tight coupling between the extension and the framework's internals. When you change a protected method's signature, every override breaks silently.

**Why the port/SPI split matters:** The port interface is what core code depends on. The SPI interface is what extension authors implement. These are sometimes the same (as with `TokenRevocationRepository`), but often differ (the SPI adds lifecycle methods like `priority()`, `isAvailable()`, and `createXxx()` that the core never sees). This separation means core logic never needs to know how its dependencies were discovered or instantiated.

---

## 10.2 TokenRevocationRepository SPI

Token revocation is the canonical example of an SPI done right. The interface lives in `aussie.spi` and defines exactly six methods -- no more, no less.

From `api/src/main/java/aussie/spi/TokenRevocationRepository.java` (lines 35-96):

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

The methods decompose into three pairs: single-token revocation (`revoke`/`isRevoked`), user-level "logout everywhere" (`revokeAllForUser`/`isUserRevoked`), and bloom filter reconstruction (`streamAllRevokedJtis`/`streamAllRevokedUsers`). Each pair serves a different operational concern, and all six are necessary.

Study the Javadoc on the interface closely (lines 8-33). It specifies:

- Entries **MUST** expire automatically based on the provided TTL
- All operations **MUST** be non-blocking (return `Uni`/`Multi`)
- Implementations **SHOULD** be thread-safe
- Implementations **SHOULD** handle connection failures gracefully

The distinction between MUST and SHOULD is deliberate. TTL expiration and non-blocking are hard requirements because the core makes assumptions about both. Thread safety and graceful failure handling are strongly recommended but left to the implementor's judgment based on their deployment context.

### The Redis Reference Implementation

The default implementation at `api/src/main/java/aussie/adapter/out/storage/redis/RedisTokenRevocationRepository.java` demonstrates the contract in practice. Several design decisions are worth highlighting.

**Key format convention** (lines 50-51):

```java
private static final String JTI_PREFIX = "aussie:revoked:jti:";
private static final String USER_PREFIX = "aussie:revoked:user:";
```

Namespaced keys prevent collision with other Redis users. This is an adapter concern -- the core interface says nothing about key format.

**Fail-closed for security-critical reads** (lines 86-94):

```java
@Override
public Uni<Boolean> isRevoked(String jti) {
    var key = JTI_PREFIX + jti;
    var operation = keyCommands.exists(key);
    // Fail-closed: return true on timeout (treat as revoked for security)
    return timeoutHelper.withTimeoutFallback(operation, "isRevoked", () -> {
        LOG.warnf("Revocation check timed out for jti %s, treating as revoked (fail-closed)", jti);
        return true;
    });
}
```

When Redis is unreachable, the implementation treats the token as revoked. This is a security policy decision that belongs in the adapter, not the interface. A different team might choose fail-open if availability matters more than security in their context. The interface deliberately does not dictate this -- the Javadoc says implementations SHOULD handle connection failures gracefully, but does not prescribe how.

**Efficient streaming with SCAN** (lines 143-152):

```java
@Override
public Multi<String> streamAllRevokedJtis() {
    var args = new KeyScanArgs().match(JTI_PREFIX + "*").count(1000);
    return keyCommands.scan(args).toMulti().map(key -> key.substring(JTI_PREFIX.length()));
}

@Override
public Multi<String> streamAllRevokedUsers() {
    var args = new KeyScanArgs().match(USER_PREFIX + "*").count(1000);
    return keyCommands.scan(args).toMulti().map(key -> key.substring(USER_PREFIX.length()));
}
```

The implementation uses Redis SCAN (not KEYS) to avoid blocking the Redis server. A Memcached implementation would not support key enumeration at all -- and that is fine. The documentation for the SPI explicitly notes this trade-off, as shown in the example Memcached implementation in `docs/platform/token-revocation-spi.md` (lines 346-356):

```java
@Override
public Multi<String> streamAllRevokedJtis() {
    // Memcached doesn't support key iteration
    // Option 1: Maintain a separate set of keys
    // Option 2: Return empty (bloom filter won't be populated from store)
    return Multi.createFrom().empty();
}
```

This is the SPI working as intended: a Memcached-backed deployment would sacrifice bloom filter rebuild from storage but gain Memcached's operational simplicity. The interface permits this, and the documentation is honest about the trade-off.

**The `@DefaultBean` annotation** (line 45):

```java
@ApplicationScoped
@DefaultBean
public class RedisTokenRevocationRepository implements TokenRevocationRepository {
```

This is the Quarkus mechanism for declaring "use this unless someone provides an alternative." The corresponding SPI Javadoc (lines 23-31 of `TokenRevocationRepository.java`) shows the pattern for overriding:

```java
@Alternative
@Priority(1)
@ApplicationScoped
public class MemcachedTokenRevocationRepository implements TokenRevocationRepository {
    // Custom implementation using AWS ElastiCache Memcached
}
```

**What a senior might do instead:** Hard-code Redis calls throughout the revocation service, or provide a single configuration switch between "redis" and "memcached" with an if/else in a factory. The hard-coding approach means every new storage backend requires modifying the core. The if/else factory approach means every new backend requires modifying the factory.

**Trade-offs of the SPI approach:** More interfaces, more indirection, slower to understand for newcomers. The payoff is that a team you have never met can drop in a DynamoDB implementation without asking you to merge a pull request.

---

## 10.3 RevocationEventPublisher SPI

Single-instance revocation is straightforward. Multi-instance revocation is where the complexity lives. When instance A revokes a token, instances B through N need to know about it immediately -- not at the next bloom filter rebuild.

The `RevocationEventPublisher` at `api/src/main/java/aussie/core/port/out/RevocationEventPublisher.java` (lines 37-67) abstracts the pub/sub mechanism:

```java
public interface RevocationEventPublisher {

    Uni<Void> publishJtiRevoked(String jti, Instant expiresAt);

    Uni<Void> publishUserRevoked(String userId, Instant issuedBefore, Instant expiresAt);

    Multi<RevocationEvent> subscribe();
}
```

Three methods, two publish and one subscribe. The event types themselves are modeled as a sealed interface at `api/src/main/java/aussie/core/model/auth/RevocationEvent.java` (lines 11-29):

```java
public sealed interface RevocationEvent {

    record JtiRevoked(String jti, Instant expiresAt) implements RevocationEvent {}

    record UserRevoked(String userId, Instant issuedBefore, Instant expiresAt)
        implements RevocationEvent {}
}
```

Sealed interfaces here are a deliberate choice. The core can exhaustively match on event types, and adding a new event type requires modifying the sealed hierarchy -- which forces you to update every handler. This prevents the silent failure mode where a new event type is published but no subscriber handles it.

### Why Pub/Sub Is Behind an Interface

The Redis implementation at `api/src/main/java/aussie/adapter/out/storage/redis/RedisRevocationEventPublisher.java` uses Redis pub/sub channels with a simple string protocol (lines 82-97):

```java
@Override
public Uni<Void> publishJtiRevoked(String jti, Instant expiresAt) {
    if (!config.pubsub().enabled()) {
        return Uni.createFrom().voidItem();
    }

    var message = "jti" + MESSAGE_SEPARATOR + jti + MESSAGE_SEPARATOR + expiresAt.toEpochMilli();

    return Uni.createFrom()
            .item(() -> {
                pubsub.publish(channel, message);
                LOG.debugf("Published JTI revocation event: %s", jti);
                return null;
            })
            .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
            .replaceWithVoid();
}
```

The Javadoc on the interface (lines 10-16) explains why this abstraction exists:

> In multi-instance deployments, revocation events must be propagated to keep bloom filters synchronized across all instances. This interface abstracts the pub/sub mechanism.

Redis pub/sub is fire-and-forget with no persistence. If an instance is down when an event fires, it misses it. For many deployments, this is acceptable because the bloom filter rebuild will catch up. But a team running Kafka would get persistence, replay, and guaranteed delivery. A team using AWS SNS+SQS would get managed infrastructure with dead letter queues. The interface makes both possible.

The interface also documents the contract clearly (lines 17-22):

- Events should be delivered to all subscribed instances
- Delivery should be **best-effort** (revocation check falls back to remote store)
- All operations MUST be non-blocking

The "best-effort" specification is critical. It tells implementors that the system does not depend on pub/sub for correctness -- only for performance. If pub/sub fails, the worst case is that a revoked token succeeds for a few more minutes until the next bloom filter rebuild. The system is correct without pub/sub; pub/sub just makes it faster.

**What a senior might do instead:** Couple directly to Redis pub/sub in the revocation service, or use a Quarkus-specific event bus. Both approaches are faster to implement but lock every deployment to a specific messaging technology.

**Trade-offs:** The interface adds a layer of indirection that makes debugging harder (you need to know which implementation is active). In exchange, swapping Redis pub/sub for Kafka is a single CDI alternative bean, not a rewrite of the revocation service.

---

## 10.4 ForwardedHeaderBuilderProvider

This is one of the smaller extension points, but it illustrates an important principle: even seemingly trivial format decisions should be behind interfaces when they affect interoperability.

The port interface at `api/src/main/java/aussie/core/port/out/ForwardedHeaderBuilderProvider.java` (lines 1-11):

```java
/**
 * Provider for obtaining the configured ForwardedHeaderBuilder.
 * This allows the core to be agnostic about which header format is used
 * (X-Forwarded-* vs RFC 7239 Forwarded) - that decision is made by the adapter.
 */
public interface ForwardedHeaderBuilderProvider {

    ForwardedHeaderBuilder getBuilder();
}
```

And the builder interface it provides, at `api/src/main/java/aussie/core/port/out/ForwardedHeaderBuilder.java` (lines 8-18):

```java
public interface ForwardedHeaderBuilder {

    /**
     * Build forwarding headers to include in the proxied request.
     */
    Map<String, String> buildHeaders(GatewayRequest originalRequest, URI targetUri);
}
```

This is a provider pattern (a factory that returns a strategy). The core code calls `ForwardedHeaderBuilderProvider.getBuilder()` once and then uses the returned `ForwardedHeaderBuilder` per request. The factory at `api/src/main/java/aussie/adapter/out/http/ForwardedHeaderBuilderFactory.java` (lines 10-33) selects between the two built-in implementations based on configuration:

```java
@ApplicationScoped
public class ForwardedHeaderBuilderFactory implements ForwardedHeaderBuilderProvider {

    private final GatewayConfig config;
    private final Rfc7239ForwardedHeaderBuilder rfc7239Builder;
    private final XForwardedHeaderBuilder xForwardedBuilder;

    @Inject
    public ForwardedHeaderBuilderFactory(
            GatewayConfig config,
            Rfc7239ForwardedHeaderBuilder rfc7239Builder,
            XForwardedHeaderBuilder xForwardedBuilder) {
        this.config = config;
        this.rfc7239Builder = rfc7239Builder;
        this.xForwardedBuilder = xForwardedBuilder;
    }

    @Override
    public ForwardedHeaderBuilder getBuilder() {
        if (config.forwarding().useRfc7239()) {
            return rfc7239Builder;
        }
        return xForwardedBuilder;
    }
}
```

The two implementations handle different header formats. The legacy `XForwardedHeaderBuilder` at `api/src/main/java/aussie/adapter/out/http/XForwardedHeaderBuilder.java` (lines 20-67) produces:
- `X-Forwarded-For: <client IP>`
- `X-Forwarded-Host: <original host>`
- `X-Forwarded-Proto: <protocol>`

The standards-based `Rfc7239ForwardedHeaderBuilder` at `api/src/main/java/aussie/adapter/out/http/Rfc7239ForwardedHeaderBuilder.java` (lines 18-92) produces:
- `Forwarded: for=<client IP>;proto=<protocol>;host=<original host>`

Both handle edge cases like appending to existing forwarding headers and quoting IPv6 addresses.

**Why this matters at the staff level:** This seems like over-engineering for a two-option config switch. But consider: some organizations have internal standards for forwarding headers. Some backend services can only parse one format. A load balancer team might need a custom format that includes internal routing metadata. By putting this behind an interface now, you prevent the "we need to fork the gateway" conversation later.

**What a senior might do instead:** An if/else in the proxy code, or a `String headerFormat` config that selects between two code paths inline. This works until the third format appears, at which point you are adding a third branch to a method that should not know about header formatting at all.

---

## 10.5 RateLimiter Port

The rate limiter port at `api/src/main/java/aussie/core/port/out/RateLimiter.java` (lines 17-74) is clean and minimal:

```java
public interface RateLimiter {

    Uni<RateLimitDecision> checkAndConsume(RateLimitKey key, EffectiveRateLimit limit);

    Uni<RateLimitDecision> getStatus(RateLimitKey key, EffectiveRateLimit limit);

    Uni<Void> reset(RateLimitKey key);

    Uni<Void> removeKeysMatching(String pattern);

    boolean isEnabled();
}
```

Five methods, all speaking in domain terms (`RateLimitKey`, `EffectiveRateLimit`, `RateLimitDecision`). The interface says nothing about storage mechanism, algorithm implementation, or clustering. The core service calls `checkAndConsume` and gets back a decision. How that decision was computed -- whether by checking a local `ConcurrentHashMap` or executing a Redis Lua script -- is invisible to the caller.

### In-Memory Default

The in-memory implementation at `api/src/main/java/aussie/adapter/out/ratelimit/memory/InMemoryRateLimiter.java` (lines 39-222) uses a `ConcurrentHashMap` with automatic stale entry cleanup:

```java
public final class InMemoryRateLimiter implements RateLimiter {

    private final ConcurrentMap<String, TimestampedState> states;
    private final AlgorithmRegistry algorithmRegistry;
    private final RateLimitAlgorithm algorithm;
    private final boolean enabled;
    private final long windowMillis;
    private final LongSupplier clock;
    private final ScheduledExecutorService cleanupExecutor;
```

Notable design decisions:

1. **Testable clock** (line 49): `LongSupplier clock` instead of direct `System.currentTimeMillis()` calls. The package-private constructor (lines 74-95) accepts a custom clock, allowing tests to control time.

2. **Stale entry cleanup** (lines 187-198): Entries older than 2x the window duration are automatically purged every 60 seconds, preventing unbounded memory growth. The cleanup threshold is intentionally generous -- keeping entries for twice the window ensures that entries near the boundary are not prematurely evicted.

3. **Atomic compute** (lines 200-216): Uses `ConcurrentHashMap.compute` for atomic check-and-update. The algorithm handler receives the current state and returns both a decision and new state in a single critical section.

```java
private RateLimitDecision computeDecision(
        String cacheKey,
        RateLimitAlgorithmHandler handler,
        EffectiveRateLimit limit,
        long nowMillis) {

    final var result = new RateLimitDecision[1];

    states.compute(cacheKey, (k, current) -> {
        final var currentState = current != null ? current.state() : null;
        final var decision = handler.checkAndConsume(currentState, limit, nowMillis);
        result[0] = decision;
        return new TimestampedState(decision.newState(), nowMillis);
    });

    return result[0];
}
```

### SPI Provider for ServiceLoader Discovery

The provider interface at `api/src/main/java/aussie/spi/RateLimiterProvider.java` (lines 26-71) adds lifecycle methods that the port interface does not have:

```java
public interface RateLimiterProvider {

    int priority();

    String name();

    boolean isAvailable();

    RateLimiter createRateLimiter();
}
```

The in-memory provider at `api/src/main/java/aussie/adapter/out/ratelimit/memory/InMemoryRateLimiterProvider.java` (line 18) has priority 0 and is always available. The Redis provider at `api/src/main/java/aussie/adapter/out/ratelimit/redis/RedisRateLimiterProvider.java` (line 24) has priority 10 and is only available when Redis is configured:

```java
// InMemoryRateLimiterProvider
private static final int PRIORITY = 0;

@Override
public boolean isAvailable() {
    return true; // Always available as fallback
}

// RedisRateLimiterProvider
private static final int PRIORITY = 10;

@Override
public boolean isAvailable() {
    return redisConfigured && redisDataSource != null;
}
```

The ServiceLoader registration file at `api/src/main/resources/META-INF/services/aussie.spi.RateLimiterProvider`:

```
aussie.adapter.out.ratelimit.memory.InMemoryRateLimiterProvider
aussie.adapter.out.ratelimit.redis.RedisRateLimiterProvider
```

A team wanting to use a custom rate limiter (say, Hazelcast for clustered rate limiting without Redis) would:

1. Implement `RateLimiterProvider` with priority > 10
2. Register in `META-INF/services/aussie.spi.RateLimiterProvider`
3. Drop the JAR on the classpath

No core code changes. No pull requests to the gateway team.

**What a senior might do instead:** A single `RateLimiter` class with an internal mode switch (`if (redisEnabled) { ... } else { ... }`). This works for two implementations but does not scale, and it means the in-memory fallback code lives in the same class as the Redis code, making both harder to test.

**Trade-offs:** The provider layer adds ceremony. Each new rate limiter implementation requires three files: the implementation class, the provider class, and the `META-INF/services` entry. In exchange, you get clean separation, automatic fallback, and zero coupling between implementations.

---

## 10.6 Metrics Port

The `Metrics` port at `api/src/main/java/aussie/core/port/out/Metrics.java` (lines 10-194) is the largest port interface in the codebase, with 22 methods. This is a deliberate choice.

```java
public interface Metrics {

    boolean isEnabled();

    void recordRequest(String serviceId, String method, int statusCode);

    void recordProxyLatency(String serviceId, String method, int statusCode, long latencyMs);

    void recordGatewayResult(String serviceId, GatewayResult result);

    void recordTraffic(String serviceId, String teamId, long requestBytes, long responseBytes);

    void recordError(String serviceId, String errorType);

    void recordAuthFailure(String reason, String clientIpHash);

    void recordAuthSuccess(String method);

    void recordAccessDenied(String serviceId, String reason);

    void incrementActiveConnections();
    void decrementActiveConnections();

    void incrementActiveWebSockets();
    void decrementActiveWebSockets();

    void recordWebSocketConnect(String serviceId);
    void recordWebSocketDisconnect(String serviceId, long durationMs);
    void recordWebSocketLimitReached();

    void recordRateLimitCheck(String serviceId, boolean allowed, long remaining);
    void recordRateLimitExceeded(String serviceId, String limitType);

    void recordProxyTimeout(String serviceId, String timeoutType);
    void recordProxyConnectionFailure(String serviceId, String errorType);
    void recordJwksFetchTimeout(String jwksUriHost);
    void recordCassandraTimeout(String repository, String operation);
    void recordRedisTimeout(String repository, String operation);
    void recordRedisFailure(String repository, String operation);
}
```

Twenty-two methods is a lot for an interface. A reasonable objection would be that this violates Interface Segregation. But consider the alternative: if you split this into `RequestMetrics`, `AuthMetrics`, `ConnectionMetrics`, `ResiliencyMetrics`, and so on, every service that records different kinds of metrics needs multiple injections. The core service that handles a request might need to record a request metric, an auth metric, a latency metric, and a rate limit metric -- all in one flow.

The interface is large because the **domain of gateway observability is large**. Each method represents a distinct operational signal that platform teams need. Splitting it would not reduce the total surface area; it would just scatter it.

### The Micrometer Adapter

The implementation at `api/src/main/java/aussie/adapter/out/telemetry/GatewayMetrics.java` (lines 38-599) maps every method to Micrometer calls:

```java
@ApplicationScoped
public class GatewayMetrics implements Metrics {

    private final MeterRegistry registry;
    private final TelemetryConfig config;
    private final boolean enabled;

    @Inject
    public GatewayMetrics(MeterRegistry registry, TelemetryConfig config) {
        this.registry = registry;
        this.config = config;
        this.enabled = config != null && config.enabled() && config.metrics().enabled();
    }
```

Every method follows the same pattern: check `enabled`, then record. For example, `recordRequest` (lines 94-107):

```java
@Override
public void recordRequest(String serviceId, String method, int statusCode) {
    if (!enabled) {
        return;
    }

    Counter.builder("aussie.requests.total")
            .description("Total number of requests processed")
            .tag("service_id", nullSafe(serviceId))
            .tag("method", method)
            .tag("status", String.valueOf(statusCode))
            .tag("status_class", statusClass(statusCode))
            .register(registry)
            .increment();
}
```

The `isEnabled()` check at line 78 lets callers skip expensive metric preparation when metrics are off:

```java
@Override
public boolean isEnabled() {
    return enabled;
}
```

Core code does not call Micrometer directly. It does not know Micrometer exists. A team that uses OpenTelemetry instead of Micrometer would implement the same 22 methods using OTEL's `Meter` API. The metric names (`aussie.requests.total`, `aussie.proxy.latency`) are defined by the adapter, not the interface, so they can be whatever the observability team needs.

**What a senior might do instead:** Inject `MeterRegistry` directly into services and record metrics inline. This is faster to write, but it means every service depends on Micrometer. When someone asks "can we also send metrics to Datadog's native API?", the answer is "rewrite every service."

**Trade-offs:** The port interface creates a bottleneck: every new metric type requires adding a method to the interface, updating the Micrometer adapter, and potentially updating alternative adapters. This friction is intentional -- it forces you to think about whether a metric is truly needed by the core, or whether it belongs in an adapter-specific extension.

---

## 10.7 Contract Tests

Defining an interface is the easy part. Ensuring that every implementation of that interface behaves correctly is the hard part. Contract tests solve this.

The `TokenRevocationRepositoryContractTest` at `api/src/test/java/aussie/spi/TokenRevocationRepositoryContractTest.java` (lines 40-267) is an abstract test class with a single abstract method:

```java
public abstract class TokenRevocationRepositoryContractTest {

    protected abstract TokenRevocationRepository createRepository();

    private TokenRevocationRepository repository;

    @BeforeEach
    void setUpContract() {
        repository = createRepository();
    }
```

Implementors extend this class and provide their specific repository. That is it. Every test in the suite runs automatically.

The test class is organized into five nested groups covering the full behavioral contract:

**1. RevokeAndCheckTests** (lines 58-100) -- basic revocation:

```java
@Test
@DisplayName("revoke() then isRevoked() should return true")
void revokeAndCheck() {
    var jti = UUID.randomUUID().toString();
    var expiresAt = Instant.now().plus(Duration.ofHours(1));

    repository.revoke(jti, expiresAt).await().atMost(Duration.ofSeconds(5));

    var revoked = repository.isRevoked(jti).await().atMost(Duration.ofSeconds(5));

    assertTrue(revoked, "Token should be revoked after calling revoke()");
}
```

**2. UserRevocationTests** (lines 103-163) -- timestamp-based semantics:

```java
@Test
@DisplayName("revokeAllForUser() affects tokens issued before cutoff")
void userRevocationAffectsOldTokens() {
    var userId = "user-" + UUID.randomUUID();
    var revokedAt = Instant.now();
    var expiresAt = Instant.now().plus(Duration.ofHours(1));

    repository.revokeAllForUser(userId, revokedAt, expiresAt)
        .await().atMost(Duration.ofSeconds(5));

    // Token issued BEFORE revocation should be affected
    var issuedBefore = revokedAt.minus(Duration.ofSeconds(60));
    var revoked = repository.isUserRevoked(userId, issuedBefore)
        .await().atMost(Duration.ofSeconds(5));

    assertTrue(revoked, "Token issued before revocation should be revoked");
}

@Test
@DisplayName("revokeAllForUser() does not affect tokens issued after cutoff")
void userRevocationDoesNotAffectNewTokens() {
    var userId = "user-" + UUID.randomUUID();
    var revokedAt = Instant.now().minus(Duration.ofSeconds(120));
    var expiresAt = Instant.now().plus(Duration.ofHours(1));

    repository.revokeAllForUser(userId, revokedAt, expiresAt)
        .await().atMost(Duration.ofSeconds(5));

    var issuedAfter = revokedAt.plus(Duration.ofSeconds(60));
    var revoked = repository.isUserRevoked(userId, issuedAfter)
        .await().atMost(Duration.ofSeconds(5));

    assertFalse(revoked, "Token issued after revocation should not be revoked");
}
```

**3. StreamJtisTests and StreamUsersTests** (lines 167-227) -- bloom filter reconstruction.

**4. IsolationTests** (lines 230-266) -- the most subtle group, verifying that JTI revocation and user revocation do not interfere with each other:

```java
@Test
@DisplayName("JTI revocation should not affect user revocation")
void jtiRevocationIndependentOfUserRevocation() {
    var jti = UUID.randomUUID().toString();
    var userId = "isolation-user-" + UUID.randomUUID();
    var expiresAt = Instant.now().plus(Duration.ofHours(1));

    repository.revoke(jti, expiresAt).await().atMost(Duration.ofSeconds(5));

    var issuedAt = Instant.now().minus(Duration.ofMinutes(5));
    var userRevoked = repository.isUserRevoked(userId, issuedAt)
        .await().atMost(Duration.ofSeconds(5));

    assertFalse(userRevoked, "User should not be affected by JTI revocation");
}
```

This test catches a subtle bug: an implementation that stores JTI and user revocations in the same data structure (e.g., the same Redis hash) might accidentally cross-contaminate. The isolation tests make this a compile-time guarantee for every implementation.

### Using Contract Tests from SPI Documentation

The SPI documentation at `docs/platform/token-revocation-spi.md` (lines 228-249) shows implementors exactly how to use the contract tests:

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
        repository = new MyCustomRepository(config);
        return repository;
    }
}
```

### Why Contract Tests Are Critical for Safe Extensibility

Without contract tests, the SPI is a promise without enforcement. An implementor reads the Javadoc, writes what they think is correct, and discovers the hard way that their timestamp comparison is off by one, or their streaming method returns duplicate entries, or their JTI revocation accidentally shadow user revocation.

With contract tests, the implementor runs a single test class and gets immediate feedback on every behavioral requirement. If all tests pass, their implementation conforms to the contract. If any test fails, the test name tells them exactly which requirement they violated.

**What a senior might do instead:** Write integration tests against the Redis implementation only, and hope that other implementations behave the same way. Or document the expected behavior and trust implementors to write their own tests.

**Trade-offs:** Abstract contract tests require careful design. You cannot use framework-specific test annotations (like `@QuarkusTest`) in the abstract class because implementors might not use the same framework. You cannot assume specific timing (like TTL expiration) because different backends expire entries on different schedules. The `atMost(Duration.ofSeconds(5))` timeouts in the tests are generous to accommodate slow backends, which means the contract test suite runs slower than it theoretically could.

---

## 10.8 Bootstrap Mode

Every extension point we have discussed so far solves a technical problem: swapping storage, messaging, metrics. Bootstrap mode solves an operational problem that emerges from the security architecture itself.

The problem: the admin API requires authentication (an API key) to create API keys. On a fresh deployment with an empty database, there are no API keys. You cannot create the first key because you need a key to authenticate. This is the classic chicken-and-egg problem.

### The Bootstrap Configuration

The config interface at `api/src/main/java/aussie/core/config/BootstrapConfig.java` (lines 32-82):

```java
@ConfigMapping(prefix = "aussie.bootstrap")
public interface BootstrapConfig {

    @WithDefault("false")
    boolean enabled();

    Optional<String> key();

    @WithDefault("PT24H")
    Duration ttl();

    @WithDefault("false")
    boolean recoveryMode();
}
```

Four configuration properties, each with a specific security rationale:

1. **`enabled`** defaults to `false`. Bootstrap mode must be explicitly activated. A production system running normally should never have this enabled.

2. **`key`** is an `Optional<String>` with no default. The administrator must provide the key via `AUSSIE_BOOTSTRAP_KEY`. The system never generates a key for you -- that would mean the key appears in logs or stdout, which is a secret exposure.

3. **`ttl`** defaults to 24 hours with a hard maximum of 24 hours. You cannot create a permanent bootstrap key.

4. **`recoveryMode`** defaults to `false`. Normal bootstrap only runs when no admin keys exist. Recovery mode overrides this check for emergency lockout scenarios.

### The Bootstrap Service

The service at `api/src/main/java/aussie/core/service/common/BootstrapService.java` (lines 34-161) enforces invariants that cannot be expressed in configuration alone:

```java
private static final Duration MAX_BOOTSTRAP_TTL = Duration.ofHours(24);
private static final int MIN_KEY_LENGTH = 32;
```

The `enforceTtlLimit` method (lines 131-149) is where the 24-hour cap is enforced:

```java
private Duration enforceTtlLimit(Duration requestedTtl) {
    if (requestedTtl == null) {
        LOG.warn("Bootstrap TTL not specified, using default 24 hours");
        return MAX_BOOTSTRAP_TTL;
    }

    if (requestedTtl.isNegative() || requestedTtl.isZero()) {
        throw new BootstrapException("Bootstrap TTL must be positive");
    }

    if (requestedTtl.compareTo(MAX_BOOTSTRAP_TTL) > 0) {
        LOG.warnf(
                "Bootstrap TTL %s exceeds maximum %s, capping to maximum",
                formatDuration(requestedTtl), formatDuration(MAX_BOOTSTRAP_TTL));
        return MAX_BOOTSTRAP_TTL;
    }

    return requestedTtl;
}
```

Note the design decision: instead of rejecting TTL values over 24 hours, it caps them with a warning. This is operator-friendly -- a misconfigured 48-hour TTL does not prevent startup, it just gets silently capped. But the minimum key length (32 characters) is a hard failure at lines 63-66:

```java
if (plaintextKey.length() < MIN_KEY_LENGTH) {
    throw new BootstrapException("Bootstrap key must be at least " + MIN_KEY_LENGTH + " characters. "
            + "Received: " + plaintextKey.length() + " characters.");
}
```

This asymmetry is deliberate: a short key is a security vulnerability that should not be worked around. A slightly-too-long TTL is an operational preference that the system can safely override.

### The Bootstrap Initializer

The startup hook at `api/src/main/java/aussie/adapter/in/bootstrap/BootstrapInitializer.java` (lines 36-136) handles the Quarkus lifecycle integration. Its most interesting aspect is the explicit documentation of its blocking behavior (lines 51-67):

```java
/**
 * <p><strong>Note on blocking behavior:</strong> This method intentionally uses
 * {@code .await().indefinitely()} to block on reactive calls. This is required
 * because Quarkus {@link StartupEvent} observers must be synchronous...
 *
 * <p>This is an acceptable use of blocking because:
 * <ul>
 *   <li>It only runs once during application startup</li>
 *   <li>The application is not yet serving traffic</li>
 *   <li>Startup events have no alternative async mechanism</li>
 * </ul>
 */
```

In a codebase that otherwise enforces non-blocking patterns (every SPI method returns `Uni` or `Multi`), this explicit justification for blocking is essential. Without it, a code reviewer would rightfully flag `await().indefinitely()` as a violation of the project's reactive discipline.

The failure behavior is also explicitly designed (lines 94-108):

```java
try {
    // INTENTIONAL BLOCKING: Startup events must be synchronous
    BootstrapResult result = bootstrapService.bootstrap().await().indefinitely();
    logBootstrapResult(result);
} catch (BootstrapException e) {
    LOG.error("========================================");
    LOG.errorf("BOOTSTRAP FAILED: %s", e.getMessage());
    LOG.error("========================================");
    throw e; // Re-throw to fail startup
}
```

Bootstrap failure kills the application. This is the correct behavior: if you enabled bootstrap mode but the key is missing or too short, starting the application without admin access would leave it in an unmanageable state.

### Recovery Mode

The `shouldBootstrap` method in `BootstrapService` (lines 106-116) shows how recovery mode works:

```java
@Override
public Uni<Boolean> shouldBootstrap() {
    if (!config.enabled()) {
        return Uni.createFrom().item(false);
    }

    if (config.recoveryMode()) {
        return Uni.createFrom().item(true); // Recovery mode always allows bootstrap
    }

    return hasAdminKeys().map(hasKeys -> !hasKeys);
}
```

Normal mode: bootstrap only if no admin keys exist. Recovery mode: bootstrap always. The initializer logs a security warning when recovery mode creates a key despite existing keys (lines 117-122):

```java
if (result.wasRecovery()) {
    LOG.warn("========================================");
    LOG.warn("RECOVERY MODE: Bootstrap key created despite existing admin keys");
    LOG.warn("This may indicate a security incident. Review immediately.");
    LOG.warn("========================================");
}
```

The `BootstrapResult` record at `api/src/main/java/aussie/core/model/common/BootstrapResult.java` (lines 15-39) captures whether this was a recovery operation:

```java
public record BootstrapResult(String keyId, Instant expiresAt, boolean wasRecovery) {

    public BootstrapResult {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("Key ID cannot be null or blank");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("Bootstrap keys must have an expiration");
        }
    }

    public static BootstrapResult standard(String keyId, Instant expiresAt) {
        return new BootstrapResult(keyId, expiresAt, false);
    }

    public static BootstrapResult recovery(String keyId, Instant expiresAt) {
        return new BootstrapResult(keyId, expiresAt, true);
    }
}
```

The compact constructor enforces that bootstrap keys always have an expiration. There is no code path that can create a bootstrap key without an `expiresAt`.

**What a senior might do instead:** A CLI tool that directly inserts into the database, bypassing the application entirely. Or a special "first run" mode that disables auth for the first 10 minutes. Both have serious security implications: the CLI tool requires direct database access (which platform teams may not have), and the timed auth-disable window is a race condition that an attacker can exploit.

**Trade-offs:** The bootstrap design requires the operator to provide a key via environment variable, which means they need access to the deployment configuration (Kubernetes secrets, environment files, etc.). This is a deliberate security-for-convenience trade-off. It is more work than auto-generation, but it ensures the operator always knows the initial credential and that it never appears in application logs.

---

## 10.9 ServiceLoader Discovery

Java's `ServiceLoader` is the standard mechanism for SPI discovery. Aussie uses it extensively, with a consistent pattern for discovery, priority-based selection, and availability checking.

The `StorageProviderLoader` at `api/src/main/java/aussie/adapter/out/storage/StorageProviderLoader.java` (lines 38-210) demonstrates the complete pattern:

```java
@ApplicationScoped
public class StorageProviderLoader {

    private final Optional<String> configuredRepositoryProvider;
    private final Optional<String> configuredCacheProvider;
    private final boolean cacheEnabled;
    private final StorageAdapterConfig config;
```

**Phase 1: Discovery** (lines 110-131):

```java
private synchronized StorageRepositoryProvider getRepositoryProvider() {
    if (repositoryProvider != null) {
        return repositoryProvider;
    }

    List<StorageRepositoryProvider> providers = new ArrayList<>();
    ServiceLoader.load(StorageRepositoryProvider.class).forEach(providers::add);

    if (providers.isEmpty()) {
        throw new StorageProviderException(
                "No storage repository providers found. "
                + "Ensure a provider JAR is on the classpath.");
    }

    LOG.infof(
            "Found %d repository provider(s): %s",
            providers.size(),
            providers.stream().map(StorageRepositoryProvider::name).toList());

    repositoryProvider = selectProvider(
        providers, configuredRepositoryProvider.orElse(null), "repository");

    return repositoryProvider;
}
```

**Phase 2: Selection** (lines 163-179):

```java
private <T> T selectProvider(List<? extends T> providers, String configured, String type) {
    // Explicit configuration takes precedence
    if (configured != null && !configured.isBlank()) {
        return providers.stream()
                .filter(p -> getName(p).equals(configured))
                .findFirst()
                .orElseThrow(() -> new StorageProviderException(
                    "Configured " + type + " provider not found: " + configured
                    + ". Available: " + providers.stream().map(this::getName).toList()));
    }

    // Otherwise, select by priority from available providers
    return providers.stream()
            .filter(this::isAvailable)
            .max(Comparator.comparingInt(this::getPriority))
            .orElseThrow(() -> new StorageProviderException(
                "No available " + type + " providers"));
}
```

The selection algorithm is simple and predictable:

1. If a provider name is explicitly configured, use that provider or fail with a clear error listing available alternatives.
2. Otherwise, filter to available providers and select the highest priority.

This two-level selection means deployments work out of the box (highest priority available provider wins) but can be pinned to a specific provider when needed.

**Phase 3: Production** -- The loader produces CDI beans via `@Produces` (lines 63-69):

```java
@Produces
@ApplicationScoped
public ServiceRegistrationRepository repository() {
    StorageRepositoryProvider provider = getRepositoryProvider();
    LOG.infof("Creating repository from provider: %s (%s)",
        provider.name(), provider.description());
    return provider.createRepository(config);
}
```

### The Configuration Bridge

The `StorageAdapterConfig` interface at `api/src/main/java/aussie/spi/StorageAdapterConfig.java` (lines 14-83) is the SPI's escape hatch for custom configuration. Instead of forcing SPI implementations to depend on Quarkus configuration, it provides a framework-agnostic configuration accessor:

```java
public interface StorageAdapterConfig {

    String getRequired(String key);

    Optional<String> get(String key);

    String getOrDefault(String key, String defaultValue);

    Map<String, String> getWithPrefix(String prefix);

    Optional<Integer> getInt(String key);

    Optional<Long> getLong(String key);

    Optional<Boolean> getBoolean(String key);

    Optional<Duration> getDuration(String key);
}
```

This means a DynamoDB storage provider can read `aussie.storage.dynamodb.region` without importing any Quarkus classes. The config bridge is implemented by an adapter class that delegates to Quarkus's `Config` API, but the SPI consumer never sees that.

### The Security Event Dispatcher

The `SecurityEventDispatcher` at `api/src/main/java/aussie/adapter/out/telemetry/SecurityEventDispatcher.java` (lines 30-149) shows a different ServiceLoader pattern -- multiple handlers receiving the same event, rather than selecting a single provider:

```java
@PostConstruct
void init() {
    if (!enabled) {
        LOG.debug("Security monitoring is disabled - event dispatcher inactive");
        return;
    }

    var loadedHandlers = ServiceLoader.load(SecurityEventHandler.class).stream()
            .map(ServiceLoader.Provider::get)
            .toList();

    // Filter available and sort by priority
    handlers = loadedHandlers.stream()
            .filter(SecurityEventHandler::isAvailable)
            .sorted(Comparator.comparingInt(SecurityEventHandler::priority).reversed())
            .toList();
```

Events are dispatched asynchronously to all handlers, highest priority first (lines 125-139):

```java
public void dispatch(SecurityEvent event) {
    if (!enabled || handlers == null || handlers.isEmpty()) {
        return;
    }

    executor.submit(() -> {
        for (var handler : handlers) {
            try {
                handler.handle(event);
            } catch (Exception e) {
                LOG.warnf("Handler %s failed to process event: %s",
                    handler.name(), e.getMessage());
            }
        }
    });
}
```

Each handler is wrapped in a try/catch so one handler's failure does not prevent others from processing the event. The events themselves use a sealed interface hierarchy (from `api/src/main/java/aussie/spi/SecurityEvent.java`), which means handlers can use pattern matching:

```java
public class PagerDutySecurityEventHandler implements SecurityEventHandler {
    @Override
    public void handle(SecurityEvent event) {
        if (event instanceof SecurityEvent.DosAttackDetected dos) {
            pagerDutyClient.trigger(createIncident(dos));
        }
    }
}
```

The built-in handlers registered in `META-INF/services/aussie.spi.SecurityEventHandler`:

```
aussie.adapter.out.telemetry.LoggingSecurityEventHandler
aussie.adapter.out.telemetry.MetricsSecurityEventHandler
```

A platform team can add their PagerDuty, Slack, or webhook handler by adding a third line to their own `META-INF/services` file.

### The Priority Convention

Across all SPI providers in the codebase, there is a consistent priority convention:

| Priority | Meaning | Examples |
|----------|---------|---------|
| 0 | Fallback/default | In-memory rate limiter, logging event handler |
| 10 | Production default | Redis rate limiter, Cassandra storage, metrics event handler |
| 100+ | Custom implementations | Reserved for platform team overrides |

This convention is documented in every provider interface. From `RateLimiterProvider.java` (lines 35-39):

```java
 * <p>Standard priorities:
 * <ul>
 *   <li>0 - In-memory (fallback)</li>
 *   <li>10 - Redis (production default)</li>
 *   <li>100+ - Custom implementations</li>
 * </ul>
```

**What a senior might do instead:** Use CDI qualifiers or Spring profiles to select implementations. This works within a single framework but is opaque to external teams who may not be using the same DI framework. ServiceLoader is a JDK standard -- any Java developer knows how to use it, regardless of their framework experience.

**Trade-offs:** ServiceLoader has limitations. It requires a no-arg constructor (which is why the providers have both a default constructor and a `configured()` factory method). It does not support dependency injection natively (which is why the `SecurityEventDispatcher` manually injects `MeterRegistry` into the `MetricsSecurityEventHandler`). And it discovers everything on the classpath, which can cause conflicts in fat-JAR deployments if two JARs register providers for the same service.

---

## Summary

SPI design is staff-level work because it requires thinking about users you will never meet, use cases you cannot predict, and integration patterns you will not be present to debug.

The key principles demonstrated in Aussie's extension points:

1. **Ports define what core needs. SPIs define how extensions register.** These are often similar but not identical -- the SPI adds lifecycle concerns (priority, availability, factory methods) that core code should not see.

2. **Contract tests are the only reliable way to ensure SPI compliance.** Documentation is necessary but insufficient. An abstract test class that implementors extend gives them immediate feedback on every behavioral requirement.

3. **Priority-based ServiceLoader discovery with explicit override capability** gives you sensible defaults (highest priority available provider) with an escape hatch (explicit configuration).

4. **Reference implementations are documentation.** The Redis implementation of `TokenRevocationRepository` shows implementors exactly what "correct" looks like, including edge cases like TTL calculation for already-expired tokens and fail-closed behavior on timeouts.

5. **Bootstrap mode solves operational problems that emerge from security architecture.** The chicken-and-egg problem of "need a key to create a key" is not a bug -- it is an inherent consequence of designing a secure admin API. Solving it requires careful thinking about key lifecycle, TTL enforcement, and recovery scenarios.

6. **Every SPI should ship with at least two implementations.** In-memory and Redis for rate limiting. X-Forwarded-* and RFC 7239 for forwarding headers. Logging and metrics for security events. Two implementations prove the interface is general enough to support variation. One implementation proves nothing -- it might be accidentally coupled to implementation details.

The test for whether your SPI design succeeded is not whether it is elegant. It is whether a team you have never met, using infrastructure you have never seen, can extend your system by implementing an interface and dropping a JAR on the classpath -- and have confidence that it works because the contract tests pass.
