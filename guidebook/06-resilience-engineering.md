# Chapter 6: Resilience Engineering -- Timeouts, Pools, and Graceful Degradation

A gateway sits between every client and every backend service. When it fails, nothing works. When it slows down, everything slows down. Resilience engineering in this context is not about adding retry libraries or circuit breaker annotations. It is about understanding what should happen when things go wrong and encoding those decisions explicitly into the system.

This chapter walks through how Aussie handles failure at every layer: how timeouts are chosen, how connection pools serve as bulkheads, why some failures are absorbed silently while others halt the request, and why the codebase deliberately avoids several patterns that most engineers reach for instinctively.

---

## 1. Timeout Taxonomy

Not all timeouts are the same. A connect timeout bounds the TCP handshake. A request timeout bounds the full round-trip. A query timeout bounds a database operation. An operation timeout bounds a Redis command. Each serves a different purpose, has a different expected latency profile, and carries different failure semantics.

Aussie centralizes all timeout configuration in a single `ResiliencyConfig` interface:

```java
// api/src/main/java/aussie/core/config/ResiliencyConfig.java, lines 21-198

@ConfigMapping(prefix = "aussie.resiliency")
public interface ResiliencyConfig {

    HttpConfig http();
    JwksConfig jwks();
    CassandraConfig cassandra();
    RedisConfig redis();

    interface HttpConfig {
        @WithDefault("PT30S")
        Duration requestTimeout();

        @WithDefault("PT5S")
        Duration connectTimeout();

        @WithDefault("50")
        int maxConnectionsPerHost();

        @WithDefault("200")
        int maxConnections();
    }

    interface CassandraConfig {
        @WithDefault("PT5S")
        Duration queryTimeout();

        @WithDefault("30")
        int poolLocalSize();

        @WithDefault("1024")
        int maxRequestsPerConnection();
    }

    interface RedisConfig {
        @WithDefault("PT1S")
        Duration operationTimeout();

        @WithDefault("30")
        int poolSize();

        @WithDefault("100")
        int poolWaiting();
    }

    interface JwksConfig {
        @WithDefault("PT5S")
        Duration fetchTimeout();

        @WithDefault("100")
        int maxCacheEntries();

        @WithDefault("PT1H")
        Duration cacheTtl();

        @WithDefault("10")
        int maxConnections();
    }
}
```

The concrete values in `application.properties` (lines 75-120):

```properties
aussie.resiliency.http.connect-timeout=PT5S
aussie.resiliency.http.request-timeout=PT30S
aussie.resiliency.cassandra.query-timeout=PT5S
aussie.resiliency.redis.operation-timeout=PT1S
aussie.resiliency.jwks.fetch-timeout=PT5S
```

### Why Each Value Is Different

**Connect timeout (5s)**: This bounds the TCP handshake only. If a host cannot complete a three-way handshake in 5 seconds, it is down. Making this shorter than the request timeout prevents wasting a full 30 seconds on a host that will never respond. Making it too short (sub-second) causes false failures under transient network congestion.

**Request timeout (30s)**: This bounds the full HTTP round-trip after the connection is established. It must accommodate slow-but-legitimate upstream responses: large payloads, complex queries, cold starts. Thirty seconds is generous. In production, most API teams override this to something tighter per service registration. The gateway default is a safety net, not a target.

**Query timeout (5s)**: Cassandra queries in this gateway are simple key lookups and range scans over service registrations and rate limit configurations. If a query takes 5 seconds, something is wrong with the cluster, not the query. This value is deliberately tight to fail fast and let the gateway serve requests from its cache tier.

**Redis operation timeout (1s)**: Redis operations should complete in single-digit milliseconds. A 1-second timeout is already an order of magnitude above normal latency. If Redis is responding this slowly, it is either overloaded or partitioned, and the gateway needs to degrade gracefully rather than pile up waiting connections.

**JWKS fetch timeout (5s)**: Fetching JSON Web Key Sets involves an external HTTP call to an identity provider. These endpoints are typically fast but sometimes fronted by CDNs with cold-start behavior. Five seconds provides enough room for a CDN cache miss without holding up JWT validation for too long.

### What a Senior Engineer Might Do Differently

A common alternative is to use a single global timeout and override it per-call. This is simpler to configure but harder to reason about. When you have a single `timeout=5s` property, you cannot distinguish between "Redis is slow" and "the upstream API is slow." The granular approach in `ResiliencyConfig` makes each timeout decision visible and independently tunable. The trade-off is configuration surface area: more knobs to turn means more knobs to misconfigure. The mitigation is sensible defaults and clear documentation on each property.

---

## 2. The RedisTimeoutHelper Framework

Redis is used for multiple purposes in Aussie: session storage, cache reads, rate limit state, token revocation checks, and cache writes. Each purpose has a different answer to the question: "What should happen when Redis is unavailable?"

Rather than scattering timeout and recovery logic across every Redis repository, Aussie centralizes it in `RedisTimeoutHelper`, which provides four modes:

```java
// api/src/main/java/aussie/adapter/out/storage/redis/RedisTimeoutHelper.java, lines 35-213

public class RedisTimeoutHelper {

    private final Duration timeout;
    private final Metrics metrics;
    private final String repositoryName;

    public RedisTimeoutHelper(Duration timeout, Metrics metrics, String repositoryName) {
        this.timeout = timeout;
        this.metrics = metrics;
        this.repositoryName = repositoryName;
    }
    // ... four modes follow
}
```

### Mode 1: `withTimeout()` -- Fail-Fast

```java
// RedisTimeoutHelper.java, lines 69-75

public <T> Uni<T> withTimeout(Uni<T> operation, String operationName) {
    return operation.ifNoItem().after(timeout).failWith(() -> {
        LOG.warnv("Redis operation timeout: {0} in {1} after {2}",
                operationName, repositoryName, timeout);
        recordTimeout(operationName);
        return new RedisTimeoutException(operationName, repositoryName);
    });
}
```

This mode converts a timeout into a `RedisTimeoutException` and lets other failures (connection errors, serialization errors) propagate unchanged. It is the strictest mode.

**Used for**: Session management. If the gateway cannot read or write a session to Redis, the operation has failed. There is no meaningful fallback -- you cannot fabricate session state.

```java
// api/src/main/java/aussie/adapter/out/storage/redis/RedisSessionRepository.java, line 66
return timeoutHelper.withTimeout(operation, "saveIfAbsent");

// line 79
return timeoutHelper.withTimeout(operation, "save");

// line 91
return timeoutHelper.withTimeout(operation, "findById");
```

Every session operation uses `withTimeout()`. A session lookup that fails with a timeout results in an error response to the client, not a silent degradation.

### Mode 2: `withTimeoutGraceful()` -- Fail-Soft to Empty

```java
// RedisTimeoutHelper.java, lines 89-109

public <T> Uni<Optional<T>> withTimeoutGraceful(Uni<T> operation, String operationName) {
    return operation
            .map(Optional::ofNullable)
            .ifNoItem()
            .after(timeout)
            .recoverWithItem(() -> {
                LOG.warnv("Redis operation timeout (graceful): {0} in {1} after {2}",
                        operationName, repositoryName, timeout);
                recordTimeout(operationName);
                return Optional.empty();
            })
            .onFailure()
            .recoverWithItem(error -> {
                LOG.warnv("Redis operation failure (graceful): {0} in {1}: {2}",
                        operationName, repositoryName, error.getMessage());
                recordFailure(operationName);
                return Optional.empty();
            });
}
```

This mode absorbs both timeouts and failures, returning `Optional.empty()`. The caller cannot distinguish between "value not in cache" and "cache is down."

**Used for**: Cache reads. If Redis is unavailable, the gateway falls through to the underlying storage (Cassandra or in-memory). A cache miss is always a valid outcome.

```java
// api/src/main/java/aussie/adapter/out/storage/redis/RedisConfigurationCache.java, line 50
return timeoutHelper.withTimeoutGraceful(operation, "get");

// api/src/main/java/aussie/adapter/out/storage/redis/RedisAuthKeyCache.java, line 51
return timeoutHelper.withTimeoutGraceful(operation, "get");
```

### Mode 3: `withTimeoutFallback()` -- Fail-Open with Custom Value

```java
// RedisTimeoutHelper.java, lines 123-142

public <T> Uni<T> withTimeoutFallback(
        Uni<T> operation, String operationName, Supplier<T> fallback) {
    return operation
            .ifNoItem()
            .after(timeout)
            .recoverWithItem(() -> {
                LOG.warnv("Redis operation timeout (fallback): {0} in {1} after {2}",
                        operationName, repositoryName, timeout);
                recordTimeout(operationName);
                return fallback.get();
            })
            .onFailure()
            .recoverWithItem(error -> {
                LOG.warnv("Redis operation failure (fallback): {0} in {1}: {2}",
                        operationName, repositoryName, error.getMessage());
                recordFailure(operationName);
                return fallback.get();
            });
}
```

This mode returns a caller-specified value on any failure. The fallback value encodes the failure policy decision.

**Used for**: Rate limiting (fail-open) and token revocation (fail-closed). Same method, opposite policies:

```java
// api/src/main/java/aussie/adapter/out/storage/redis/RedisFailedAttemptRepository.java, line 90
// Fail-open: return 0 on timeout (allow request)
return timeoutHelper.withTimeoutFallback(operation, "recordFailedAttempt", () -> 0L);

// line 98
// Fail-open: return 0 on timeout (no failed attempts = allow)
return timeoutHelper.withTimeoutFallback(operation, "getFailedAttemptCount", () -> 0L);

// line 146
// Fail-open: return false on timeout (not locked out = allow)
return timeoutHelper.withTimeoutFallback(operation, "isLockedOut", () -> false);
```

```java
// api/src/main/java/aussie/adapter/out/storage/redis/RedisTokenRevocationRepository.java, lines 90-93
// Fail-closed: return true on timeout (treat as revoked for security)
return timeoutHelper.withTimeoutFallback(operation, "isRevoked", () -> {
    LOG.warnf("Revocation check timed out for jti %s, treating as revoked (fail-closed)", jti);
    return true;
});

// lines 136-139
// Fail-closed: return true on timeout (treat as revoked for security)
return timeoutHelper.withTimeoutFallback(operation, "isUserRevoked", () -> {
    LOG.warnf("User revocation check timed out for %s, treating as revoked (fail-closed)", userId);
    return true;
});
```

The critical insight is that the same `withTimeoutFallback` method serves both fail-open and fail-closed semantics. The policy is in the fallback supplier, not in the framework.

### Mode 4: `withTimeoutSilent()` -- Fire-and-Forget

```java
// RedisTimeoutHelper.java, lines 154-173

public Uni<Void> withTimeoutSilent(Uni<Void> operation, String operationName) {
    return operation
            .ifNoItem()
            .after(timeout)
            .recoverWithItem(() -> {
                LOG.warnv("Redis operation timeout (silent): {0} in {1} after {2}",
                        operationName, repositoryName, timeout);
                recordTimeout(operationName);
                return null;
            })
            .onFailure()
            .recoverWithItem(error -> {
                LOG.warnv("Redis operation failure (silent): {0} in {1}: {2}",
                        operationName, repositoryName, error.getMessage());
                recordFailure(operationName);
                return null;
            });
}
```

This mode absorbs all failures silently. The operation was never critical.

**Used for**: Cache writes, lockout recording, token revocation writes, and cache invalidation:

```java
// RedisConfigurationCache.java, line 63
return timeoutHelper.withTimeoutSilent(operation, "put");

// RedisAuthKeyCache.java, line 61
return timeoutHelper.withTimeoutSilent(operation, "put");

// RedisFailedAttemptRepository.java, line 108
return timeoutHelper.withTimeoutSilent(operation, "clearFailedAttempts");

// RedisTokenRevocationRepository.java, line 82
return timeoutHelper.withTimeoutSilent(operation, "revoke");
```

Cache writes that fail are not catastrophic -- the next cache miss will repopulate from storage. Revocation writes that fail are more concerning, but the bloom filter and pub/sub mechanisms provide secondary consistency.

### Why Not Use a Library?

MicroProfile Fault Tolerance, Resilience4j, and SmallRye Fault Tolerance all provide timeout and fallback annotations. The reason Aussie rolls its own is control. Annotation-based fault tolerance operates at method boundaries. `RedisTimeoutHelper` operates at operation boundaries within a single reactive chain. A single repository method might need different failure modes for different Redis calls within the same pipeline. Annotations cannot express "fail-open for the rate limit read but fail-silent for the cleanup write."

The trade-off is that every Redis repository must explicitly choose a mode. This is deliberate: forcing developers to think about failure semantics for every Redis interaction prevents the default from being wrong.

---

## 3. Fail-Open vs. Fail-Closed Decisions

The most important resilience decision in a gateway is not "what timeout value?" but "which direction do we fail?"

### Rate Limiting: Fail-Open

When Redis is unavailable, rate limiting returns fallback values that allow the request to proceed:

```java
// RedisFailedAttemptRepository.java, line 90
return timeoutHelper.withTimeoutFallback(operation, "recordFailedAttempt", () -> 0L);

// line 146
return timeoutHelper.withTimeoutFallback(operation, "isLockedOut", () -> false);
```

**Why**: The alternative is to reject all traffic when Redis is down. For a gateway handling thousands of requests per second, this turns a Redis outage into a total service outage. The purpose of rate limiting is to protect backends from abuse, not to be a mandatory gate for every request. During a Redis outage, backends are still protected by their own capacity limits and the gateway's connection pool bulkheads. The risk of allowing some extra traffic through for a few minutes is acceptable compared to the certainty of dropping all traffic.

### Token Revocation: Fail-Closed

When Redis is unavailable, revocation checks treat tokens as revoked:

```java
// RedisTokenRevocationRepository.java, line 90
return timeoutHelper.withTimeoutFallback(operation, "isRevoked", () -> {
    LOG.warnf("Revocation check timed out for jti %s, treating as revoked (fail-closed)", jti);
    return true;
});
```

**Why**: If you cannot verify that a token has not been revoked, you must assume it has been. A revoked token represents a compromised credential -- allowing it through could grant an attacker access to protected resources. The cost of incorrectly rejecting a valid token during a Redis outage is that a legitimate user must re-authenticate. The cost of incorrectly accepting a revoked token is a potential security breach. The asymmetry is clear.

### Session Management: Fail-Hard

Session operations use `withTimeout()` and propagate errors. There is no safe fallback. You cannot fabricate a session from nothing, and silently dropping a session save means the user's state is lost without warning.

### The Decision Framework

The pattern is:

| Question | Fail-Open | Fail-Closed | Fail-Hard |
|----------|-----------|-------------|-----------|
| Can we safely proceed without this data? | Yes | No | No |
| Is the data a performance optimization? | Yes | No | No |
| Does failure create a security risk? | No | Yes | Maybe |
| Is there a fallback that preserves correctness? | Yes (default value) | Yes (deny) | No |

Each Redis repository picks the mode that matches its answers. This is not a framework decision -- it is a product decision encoded in code.

---

## 4. Connection Pooling as Bulkhead Isolation

Connection pools serve two purposes: they reduce the cost of establishing connections, and they limit the blast radius of a slow dependency. Aussie uses separate pools for each backend:

| Component | Pool Size | Configured At |
|-----------|-----------|---------------|
| Cassandra | 30 connections/node | `aussie.resiliency.cassandra.pool-local-size=30` |
| Redis | 30 connections | `aussie.resiliency.redis.pool-size=30` |
| HTTP Proxy | 50/host, 200 total | `aussie.resiliency.http.max-connections-per-host=50` |
| JWKS Fetch | 10 connections | `aussie.resiliency.jwks.max-connections=10` |

Production overrides raise these limits (from `application.properties`, lines 61-66):

```properties
%prod.aussie.resiliency.cassandra.pool-local-size=50
%prod.aussie.resiliency.redis.pool-size=50
%prod.aussie.resiliency.redis.pool-waiting=200
%prod.aussie.resiliency.http.max-connections-per-host=100
%prod.aussie.resiliency.http.max-connections=500
%prod.aussie.resiliency.jwks.max-connections=20
```

### How Pools Act as Bulkheads

The `BulkheadHealthCheck` Javadoc explains this directly:

```java
// api/src/main/java/aussie/adapter/in/health/BulkheadHealthCheck.java, lines 31-42

// Aussie uses connection pools as bulkheads for fault isolation:
//   Event loop (CPU-bound): All CPU work runs here, pinned to CPU cores
//   IO pools (bulkheads): Each dependency has isolated connections
//
// If Cassandra is slow, only Cassandra operations are affected.
// Redis and HTTP proxy operations continue unimpacted.
```

This is the key property. Without pool isolation, a slow Cassandra cluster could cause the gateway's thread pool to fill up, blocking Redis lookups and HTTP proxy calls that have nothing to do with Cassandra. With separate pools, each dependency can only exhaust its own allocation. A Cassandra outage means service registration lookups fail, but session validation (Redis) and request proxying (HTTP) continue.

### The HTTP Proxy Pool

The HTTP pool has both per-host and global limits. The per-host limit (`50` default, `100` prod) prevents a single slow upstream from monopolizing all outbound connections. The global limit (`200` default, `500` prod) bounds total resource consumption.

```java
// api/src/main/java/aussie/adapter/out/http/ProxyHttpClient.java, lines 80-87

@PostConstruct
void init() {
    var options = new WebClientOptions()
            .setConnectTimeout((int) httpConfig.connectTimeout().toMillis());
    this.webClient = WebClient.create(vertx, options);
    LOG.infov(
            "ProxyHttpClient initialized with connect timeout: {0}, request timeout: {1}",
            httpConfig.connectTimeout(), httpConfig.requestTimeout());
}
```

Note that the connect timeout is set at WebClient creation time (it applies to all connections), while the request timeout is set per-request:

```java
// ProxyHttpClient.java, lines 208-217

private HttpRequest<Buffer> createRequest(HttpMethod method, URI targetUri) {
    var path = targetUri.getRawPath();
    if (targetUri.getRawQuery() != null) {
        path += "?" + targetUri.getRawQuery();
    }

    return webClient
            .request(method, getPort(targetUri), targetUri.getHost(), path)
            .timeout(httpConfig.requestTimeout().toMillis());
}
```

This separation matters: the connect timeout applies identically to all upstream hosts (TCP handshake latency should not vary by service), but the request timeout could theoretically be overridden per service in the future.

### Cassandra's Multiplied Capacity

Cassandra's pool is 30 connections per node, with up to 1024 concurrent requests per connection. This means a 3-node cluster can handle `30 * 3 * 1024 = 92,160` concurrent Cassandra requests. For a gateway doing simple key lookups, this is massively overprovisioned, which is the point: you never want the gateway to bottleneck on its own metadata store.

### What a Senior Engineer Might Do Differently

Some teams use dynamic pool sizing: start small, grow under load, shrink during idle periods. This saves memory but introduces latency spikes during pool growth. For a gateway, predictable latency matters more than memory savings. Fixed pools mean the connection establishment cost is paid at startup, not during a traffic spike. The trade-off is memory consumption: 50 idle Cassandra connections per node consume resources even at 2 AM. For a gateway, this is an acceptable cost.

---

## 5. Health Check Philosophy

Aussie's bulkhead health check always reports UP:

```java
// api/src/main/java/aussie/adapter/in/health/BulkheadHealthCheck.java, lines 56-68

@Override
public HealthCheckResponse call() {
    HealthCheckResponseBuilder builder = HealthCheckResponse.builder().name("bulkheads");
    builder.withData("aussie.bulkhead.cassandra.pool.max", bulkheadMetrics.getCassandraPoolSize());
    builder.withData("aussie.bulkhead.redis.pool.max", bulkheadMetrics.getRedisPoolSize());
    builder.withData("aussie.bulkhead.http.pool.max.per_host",
            bulkheadMetrics.getHttpMaxConnectionsPerHost());
    builder.withData("aussie.bulkhead.jwks.pool.max", bulkheadMetrics.getJwksMaxConnections());
    builder.withData("metrics.enabled", bulkheadMetrics.isEnabled());

    // UP if configuration is loaded; pool exhaustion is monitored via metrics/alerts
    return builder.up().build();
}
```

This looks wrong at first glance. A health check that never fails seems useless. Here is why it is correct.

### Why Pool Exhaustion Should Not Trigger Health Check Failure

Health checks serve a specific purpose: they tell the load balancer whether to route traffic to this instance. Consider what happens if pool exhaustion marks the instance as DOWN:

1. Under high load, Cassandra pool hits 100% utilization.
2. Health check reports DOWN.
3. Load balancer removes this instance from rotation.
4. Remaining instances receive MORE traffic (the traffic that was going to the removed instance).
5. Remaining instances hit pool exhaustion.
6. Cascading failure: all instances marked DOWN.

This is exactly the thundering herd problem, triggered by the health check itself. Pool exhaustion under load is transient and self-correcting: as in-flight requests complete, connections return to the pool. Removing the instance from the load balancer prevents that self-correction.

### What Should Trigger Alerts Instead

The `BulkheadMetrics` class exposes pool configuration as Prometheus gauges:

```java
// api/src/main/java/aussie/adapter/out/telemetry/BulkheadMetrics.java, lines 88-131

Gauge.builder("aussie.bulkhead.cassandra.pool.max", () -> cassandraPoolSize)
        .description("Maximum Cassandra connections per node in local datacenter")
        .tag("type", "connection_pool")
        .register(registry);

Gauge.builder("aussie.bulkhead.redis.pool.max", () -> redisPoolSize)
        .description("Maximum Redis connections in pool")
        .tag("type", "connection_pool")
        .register(registry);

Gauge.builder("aussie.bulkhead.http.pool.max.per_host", () -> httpMaxPerHost)
        .description("Maximum HTTP connections per upstream host")
        .tag("type", "connection_pool")
        .register(registry);

Gauge.builder("aussie.bulkhead.jwks.pool.max", () -> jwksMax)
        .description("Maximum concurrent JWKS fetch connections")
        .tag("type", "connection_pool")
        .register(registry);
```

Combined with the driver-provided usage gauges (Cassandra driver metrics, Quarkus Redis metrics, Vert.x HTTP metrics), platform teams can alert when actual usage exceeds 80-90% of the configured maximum for a sustained period. This is stated explicitly in `application.properties` (lines 39-41):

```properties
# ALERTING RECOMMENDATIONS:
# - Create alerts on driver-provided usage gauges at 80-90% occupancy sustained for 5+ minutes
# - Monitor aussie.bulkhead.*.pool.max metrics via /q/metrics endpoint
```

The health check exposes the configuration; the metrics system monitors the reality. Combining them in a single health check creates a coupling between operational observability and routing decisions that causes more problems than it solves.

### What a Senior Engineer Might Do Differently

Some teams implement a "degraded" health status: UP but reporting warnings. Kubernetes, however, treats liveness and readiness as binary. There is no "degraded" in the Kubernetes health model. If you report not-ready, you get removed from service. If you report not-alive, you get killed and restarted. Neither is the correct response to transient pool pressure. The right tool is alerting and dashboards, not health checks.

---

## 6. Graceful Degradation: JWKS Stale-Key Fallback

JWT validation requires the identity provider's public keys (JWKS). When the gateway cannot fetch fresh keys, it falls back to stale cached keys:

```java
// api/src/main/java/aussie/core/service/auth/JwksCacheService.java, lines 123-153

private Uni<JsonWebKeySet> fetchAndCache(URI jwksUri) {
    LOG.infov("Fetching JWKS from {0}", jwksUri);

    return webClient
            .getAbs(jwksUri.toString())
            .ssl(jwksUri.getScheme().equals("https"))
            .send()
            .ifNoItem()
            .after(jwksConfig.fetchTimeout())
            .failWith(() -> {
                LOG.warnv("JWKS fetch timeout for {0} after {1}",
                        jwksUri, jwksConfig.fetchTimeout());
                metrics.recordJwksFetchTimeout(jwksUri.getHost());
                return new JwksFetchException(
                        "Timeout fetching JWKS from " + jwksUri);
            })
            .map(this::parseResponse)
            .invoke(keySet -> {
                cache.put(jwksUri, new CachedKeySet(
                        keySet, Instant.now().plus(jwksConfig.cacheTtl())));
                LOG.infov("Cached {0} keys from {1}",
                        keySet.getJsonWebKeys().size(), jwksUri);
            })
            .onFailure()
            .recoverWithUni(error -> {
                LOG.errorv(error, "Failed to fetch JWKS from {0}", jwksUri);
                // Try to use stale cached keys if available
                var stale = cache.getIfPresent(jwksUri);
                if (stale != null) {
                    LOG.warnv("Using stale cached JWKS for {0} due to: {1}",
                            jwksUri, error.getMessage());
                    return Uni.createFrom().item(stale.keySet());
                }
                return Uni.createFrom().failure(error);
            });
}
```

The recovery logic at lines 143-153 is the key section. When a fetch fails for any reason (timeout, HTTP error, parse failure), the code checks the Caffeine cache. The `CachedKeySet` record tracks an expiration timestamp:

```java
// JwksCacheService.java, lines 184-188

private record CachedKeySet(JsonWebKeySet keySet, Instant expiresAt) {
    boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
```

A crucial detail: the normal code path at line 76 checks `isExpired()` before using cached data:

```java
// JwksCacheService.java, lines 74-80

public Uni<JsonWebKeySet> getKeySet(URI jwksUri) {
    var cached = cache.getIfPresent(jwksUri);
    if (cached != null && !cached.isExpired()) {
        LOG.debugv("Using cached JWKS for {0}", jwksUri);
        return Uni.createFrom().item(cached.keySet());
    }
    return getOrCreateFetch(jwksUri);
}
```

But the fallback code at line 147 uses `cache.getIfPresent()` without the `isExpired()` check. This is deliberate: during a fetch failure, stale keys are better than no keys. The keys were valid recently, and key rotation happens on the order of weeks or months, not minutes.

### Thundering Herd Protection

The `JwksCacheService` also prevents thundering herd on cache expiration using request coalescing:

```java
// JwksCacheService.java, lines 87-101

private Uni<JsonWebKeySet> getOrCreateFetch(URI jwksUri) {
    return Uni.createFrom().deferred(() -> {
        var fetch = inFlightFetches.computeIfAbsent(jwksUri, this::createFetch);
        return fetch;
    });
}

private Uni<JsonWebKeySet> createFetch(URI jwksUri) {
    return fetchAndCache(jwksUri)
            .onTermination()
            .invoke(() -> inFlightFetches.remove(jwksUri))
            .memoize()
            .indefinitely();
}
```

When the cache expires and 100 concurrent requests all need the JWKS, only one fetch is initiated. The `ConcurrentHashMap.computeIfAbsent` ensures atomicity, and `.memoize().indefinitely()` shares the result of the single fetch across all waiters. Without this, an identity provider outage combined with cache expiration would produce a storm of failed HTTP requests.

### When Stale Data Is Not Acceptable

The fallback only works because cryptographic key sets are append-only in practice. When a new key is added, old keys remain valid. The worst case for stale JWKS data is that the gateway cannot validate tokens signed with a brand-new key. Tokens signed with older keys continue to validate correctly.

If the identity provider has rotated all keys (removed old ones), stale cached data would cause all token validation to fail. This is an edge case that occurs only during aggressive key rotation coinciding with an IDP outage. The mitigation is the 1-hour cache TTL (`aussie.resiliency.jwks.cache-ttl=PT1H`), which limits how stale the data can be.

---

## 7. No Circuit Breaker by Design

Aussie does not use circuit breakers. This is a deliberate architectural decision, not an omission.

### What Circuit Breakers Do

A circuit breaker tracks failure rates and, when failures exceed a threshold, "opens" the circuit to reject requests immediately without attempting the downstream call. After a cooldown period, it allows a probe request through; if it succeeds, the circuit closes.

### Why They Are Unnecessary Here

For a stateless gateway, the combination of timeouts, stale data fallbacks, and reactive backpressure provides the same protections without the additional state and complexity:

**Timeouts bound the blast radius.** A slow upstream cannot consume unbounded connections because each request has a 30-second maximum lifetime. After timeout, the connection is returned to the pool.

**Stale data provides continuity.** The JWKS cache, configuration cache, and rate limit state all have graceful degradation paths. The gateway can continue functioning with cached data for the duration of most outages.

**Reactive backpressure prevents resource exhaustion.** Vert.x's event loop model means slow downstream calls do not block threads. When the HTTP connection pool fills up, new requests wait in a bounded queue (up to `maxPoolWaiting`). If the queue fills, new requests fail immediately. This is functionally equivalent to a circuit breaker's "open" state, but it happens naturally from pool exhaustion rather than requiring an explicit state machine.

**Connection pool limits act as implicit circuit breakers.** With 50 connections per upstream host, the gateway can have at most 50 in-flight requests to a single service. If that service is responding slowly, new requests will either queue (bounded) or fail (pool exhaustion). This naturally limits the damage.

### When Circuit Breakers Would Help

Circuit breakers are most valuable when:
- The downstream dependency is stateful and shared (a database that all instances write to).
- The recovery time is long and predictable (a service that takes minutes to restart).
- You need to provide a cached response while the circuit is open.

For Aussie, each request is independent. The gateway does not accumulate state that degrades over time. If Redis is down, rate limiting fails open immediately; it does not need to "learn" that Redis is down through a failure count. The timeout is the learning mechanism.

### The Trade-Off

The risk of not having circuit breakers is that the gateway will continue attempting connections to a known-dead service. Each attempt costs the connect timeout (5 seconds) before failing. A circuit breaker would skip this cost. In practice, the connect timeout is short enough that the additional latency per request during a backend outage is acceptable, and it avoids the complexity of managing circuit state, half-open probes, and the failure-rate threshold tuning that circuit breakers require.

A senior engineer who disagrees with this decision might point out that circuit breakers provide faster failure during sustained outages (milliseconds instead of 5 seconds). This is valid for latency-sensitive paths, and if the gateway evolves to have specific hot paths where the 5-second connect timeout is unacceptable, adding a targeted circuit breaker for that path would be reasonable. The argument against is that a generic circuit breaker applied to all traffic introduces a new failure mode (false opens due to transient errors) that is harder to debug than a simple timeout.

---

## 8. Connection Error Classification

When an upstream connection fails, the gateway classifies the error for metrics:

```java
// api/src/main/java/aussie/adapter/out/http/ProxyHttpClient.java, lines 181-198

private String classifyConnectionError(Throwable error) {
    var message = error.getMessage() != null ? error.getMessage().toLowerCase() : "";
    var className = error.getClass().getSimpleName().toLowerCase();

    if (message.contains("refused") || className.contains("refused")) {
        return "connection_refused";
    }
    if (message.contains("reset") || className.contains("reset")) {
        return "connection_reset";
    }
    if (message.contains("unreachable") || className.contains("unreachable")) {
        return "host_unreachable";
    }
    if (message.contains("resolve") || message.contains("unknown host")
            || className.contains("unknownhost")) {
        return "dns_resolution_failed";
    }
    return "connection_error";
}
```

This is string matching on exception messages and class names. It is fragile.

### Why This Is Necessary

Vert.x (and Netty underneath it) does not provide a clean exception hierarchy for connection failures. A `ConnectException` with message "Connection refused" and a `NoRouteToHostException` both represent network-level failures, but they require different operational responses:

- **Connection refused**: The host is up but the service is not listening. Check if the service is running.
- **Connection reset**: The host accepted the connection but killed it. Check for service crashes, firewall rules, or connection limits.
- **Host unreachable**: The host is down or there is no route. Check network configuration, security groups, or host health.
- **DNS resolution failed**: The hostname does not resolve. Check DNS configuration, service discovery, or typos in the service URL.

Without classification, all four appear as the same `connection_error` metric, making the operations team's job harder.

### Why It Is Fragile

Exception messages are not part of a public API contract. A Vert.x upgrade could change "Connection refused" to "connection refused" or "Socket connection refused." The `toLowerCase()` call mitigates case changes, but a rewording would silently degrade classification accuracy.

The timeout detection has a similar fragility:

```java
// ProxyHttpClient.java, lines 169-179

private boolean isTimeoutException(Throwable error) {
    var current = error;
    while (current != null) {
        var className = current.getClass().getName();
        if (className.contains("TimeoutException")) {
            return true;
        }
        current = current.getCause();
    }
    return false;
}
```

This walks the exception cause chain looking for any class with "TimeoutException" in the name. It works for `java.util.concurrent.TimeoutException`, `io.netty.handler.timeout.ReadTimeoutException`, and any Vert.x-specific timeout class. But it would also match a hypothetical `NotATimeoutException` whose name happens to contain the substring.

### The Alternative

A cleaner approach would be to register typed exception handlers for specific Vert.x/Netty exception types. This is more robust but creates a coupling to specific Vert.x version exception classes, which could break across major version upgrades. The string-matching approach is more fragile within a version but more resilient across versions. Given that Vert.x exception types have been stable for years and the fallback (`connection_error` / false negative for timeout) is safe, the trade-off favors simplicity.

---

## Timeout and Failure Mode Summary

| Component | Timeout | Pool Size | Failure Mode | Rationale |
|-----------|---------|-----------|--------------|-----------|
| HTTP Proxy (connect) | 5s | 50/host, 200 total | 502 Bad Gateway | Dead host detection; no fallback possible |
| HTTP Proxy (request) | 30s | (shared with connect) | 504 Gateway Timeout | Generous default; per-service override expected |
| Cassandra query | 5s | 30/node, 1024 req/conn | Propagate error | Simple lookups; 5s = cluster problem |
| Redis session ops | 1s | 30 conn, 100 waiting | Propagate error (fail-hard) | No safe fallback for session state |
| Redis cache reads | 1s | (shared pool) | `Optional.empty()` (fail-soft) | Cache miss is always valid |
| Redis rate limiting | 1s | (shared pool) | Allow request (fail-open) | Availability over rate enforcement |
| Redis revocation check | 1s | (shared pool) | Treat as revoked (fail-closed) | Security over availability |
| Redis cache writes | 1s | (shared pool) | Swallow error (fire-and-forget) | Next miss repopulates |
| JWKS fetch | 5s | 10 connections | Stale cached keys | Crypto keys change slowly |

---

## Key Takeaways

**Timeouts are not one number.** Each component has a different expected latency profile. A single global timeout either wastes time on fast operations or is too aggressive for slow ones. Name each timeout explicitly.

**Failure mode is a product decision, not an infrastructure decision.** Whether to fail open or closed depends on what the data is used for, not how it is stored. The same Redis cluster serves both rate limiting (fail-open) and token revocation (fail-closed).

**Connection pools are bulkheads.** Separate pools prevent a single slow dependency from cascading into a total outage. This isolation is more valuable than any retry policy.

**Health checks are for routing, not observability.** If a health check failure would make the problem worse (by redistributing load to already-stressed instances), the check should not fail. Use metrics and alerts for monitoring; use health checks only for binary up/down signals that the load balancer can act on safely.

**Stale data is often better than no data.** JWKS keys, configuration caches, and rate limit state all benefit from serving slightly outdated information during an outage rather than failing hard.

**Simplicity over sophistication.** Timeouts and pool limits provide most of the resilience benefits of circuit breakers without the state management complexity. Add circuit breakers when you have evidence that the 5-second connect timeout is causing measurable harm, not preemptively.
