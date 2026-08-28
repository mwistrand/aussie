# Chapter 4: Rate Limiting (Algorithm Selection, Network Identity, and Platform Guardrails)

Rate limiting in a shared API gateway is deceptively difficult. The individual algorithms are well-understood. Any senior engineer can describe a token bucket. The hard parts are everything around the algorithm: how you identify the client, how you prevent service teams from shooting themselves in the foot, how you handle the impedance mismatch between HTTP and WebSocket traffic, and how you make it all work across multiple gateway instances without blocking threads. This chapter traces those decisions through the Aussie codebase.

## 1. The Strategy Pattern: Three Algorithms, One Interface

Aussie defines three rate limiting algorithms as an enum at `api/src/main/java/aussie/core/model/ratelimit/RateLimitAlgorithm.java`:

```java
public enum RateLimitAlgorithm {
    BUCKET,
    FIXED_WINDOW,
    SLIDING_WINDOW
}
```

Each algorithm implements the `RateLimitAlgorithmHandler` interface (line 16 of `api/src/main/java/aussie/core/model/ratelimit/RateLimitAlgorithmHandler.java`):

```java
public interface RateLimitAlgorithmHandler {

    RateLimitAlgorithm algorithm();

    RateLimitDecision checkAndConsume(
        RateLimitState currentState, EffectiveRateLimit limit, long nowMillis);

    RateLimitState createInitialState(EffectiveRateLimit limit, long nowMillis);

    RateLimitDecision computeStatus(
        RateLimitState currentState, EffectiveRateLimit limit, long nowMillis);
}
```

Three things to notice about this interface.

First, **state is passed in and returned, not mutated**. The handler receives `RateLimitState`, computes a decision, and returns the new state inside `RateLimitDecision.newState()`. The handler itself is stateless. This is what makes the same algorithm work for both the in-memory store (which manages state in a `ConcurrentHashMap`) and the Redis store (which manages state in Lua scripts). The algorithm does not care where state lives.

Second, **the clock is explicit**. Every method takes `long nowMillis` rather than calling `System.currentTimeMillis()` internally. This makes the algorithms deterministic and testable. You can simulate time progression in unit tests without touching thread sleeps or mocking static methods.

Third, **`computeStatus` is separate from `checkAndConsume`**. The former reads without consuming; the latter reads, decides, and consumes atomically. This separation exists because response headers need to report current status on every request, not just on rate-limited requests. A `getStatus` default method on the interface (line 52) handles the null-state case:

```java
default RateLimitDecision getStatus(
        RateLimitState currentState, EffectiveRateLimit limit, long nowMillis) {
    if (currentState == null) {
        final var initialState = createInitialState(limit, nowMillis);
        return createAllowedDecision(initialState, limit, nowMillis);
    }
    return computeStatus(currentState, limit, nowMillis);
}
```

The `AlgorithmRegistry` (line 32 of `api/src/main/java/aussie/core/service/ratelimit/AlgorithmRegistry.java`) stores handlers in an `EnumMap` and rejects unavailable algorithms rather than silently changing their semantics:

```java
public RateLimitAlgorithmHandler getHandler(RateLimitAlgorithm algorithm) {
    final var handler = handlers.get(algorithm);
    if (handler == null) {
        throw new IllegalArgumentException(
            "Unsupported rate-limit algorithm: " + algorithm);
    }
    return handler;
}
```

Currently only `BucketAlgorithm` is registered. Selecting `FIXED_WINDOW` or `SLIDING_WINDOW` fails gateway startup until a handler and matching backend implementation exist. Adding an algorithm means implementing `RateLimitAlgorithmHandler`, adding any required `RateLimitState` implementation, registering the handler, and implementing equivalent Redis semantics.

Rejecting an unavailable algorithm is deliberate: falling back to token bucket could enforce materially different quotas than the platform team configured.

## 2. Token Bucket Internals: How the Algorithm Actually Works

The token bucket is implemented across two files: `BucketState` for the state record and `BucketAlgorithm` for the logic.

### State Representation

`BucketState` at `api/src/main/java/aussie/core/model/ratelimit/BucketState.java` (line 15) is a Java record:

```java
public record BucketState(long tokens, long lastRefillMillis) implements RateLimitState {
```

Two fields. `tokens` is the current count of available tokens. `lastRefillMillis` is the epoch millisecond timestamp of the last time tokens were added. That is the entire state. The record is immutable: every mutation returns a new instance.

### The Refill Calculation

The refill rate comes from `EffectiveRateLimit.refillRatePerSecond()` at line 69 of `api/src/main/java/aussie/core/model/ratelimit/EffectiveRateLimit.java`:

```java
public double refillRatePerSecond() {
    return (double) requestsPerWindow / windowSeconds;
}
```

If the limit is 100 requests per 60 seconds, the refill rate is ~1.667 tokens per second. The `BucketState.calculateRefillTokens` method (line 72) computes how many tokens to add based on elapsed time:

```java
public long calculateRefillTokens(double refillRatePerSecond, long nowMillis) {
    final var elapsedMillis = nowMillis - lastRefillMillis;
    final var elapsedSeconds = elapsedMillis / 1000.0;
    return (long) (elapsedSeconds * refillRatePerSecond);
}
```

The cast to `long` truncates fractional tokens. This is deliberate: you cannot use half a token. The downside is that at very low refill rates, there is a small delay before the first token appears. At 1.667 tokens/second, the first token appears after ~600ms. This is acceptable for the gateway's use case.

### The Core Algorithm

The `checkAndConsume` method in `BucketAlgorithm` (line 47 of `api/src/main/java/aussie/core/model/ratelimit/BucketAlgorithm.java`) ties it together:

```java
@Override
public RateLimitDecision checkAndConsume(
        RateLimitState currentState, EffectiveRateLimit limit, long nowMillis) {

    final var state = resolveState(currentState, limit, nowMillis);
    final var refilled = refillTokens(state, limit, nowMillis);

    if (refilled.tokens() > 0) {
        return createAllowedResult(refilled, limit, nowMillis);
    }
    return createRejectedResult(refilled, limit, nowMillis);
}
```

The flow is: resolve state (create initial if null), refill tokens based on elapsed time, then check if any tokens remain. If yes, consume one and allow. If no, reject with retry-after information.

The refill step (line 79) caps tokens at burst capacity:

```java
private BucketState refillTokens(BucketState state, EffectiveRateLimit limit, long nowMillis) {
    final var refillRate = limit.refillRatePerSecond();
    final var tokensToAdd = state.calculateRefillTokens(refillRate, nowMillis);

    if (tokensToAdd <= 0) {
        return state;
    }
    return state.refill(tokensToAdd, limit.burstCapacity(), nowMillis);
}
```

And `BucketState.refill` (line 60) enforces the cap:

```java
public BucketState refill(long tokensToAdd, long capacity, long now) {
    final var newTokens = Math.min(capacity, tokens + tokensToAdd);
    return new BucketState(newTokens, now);
}
```

This is the "burst capacity" parameter in action. Even if a client has been idle for hours, their bucket never exceeds `burstCapacity` tokens. A client cannot stockpile tokens indefinitely and then dump them all at once.

### Retry-After Calculation

When the bucket is empty, the algorithm computes how long until one token becomes available (line 119):

```java
private long calculateRetryAfter(EffectiveRateLimit limit, long nowMillis) {
    final var refillRate = limit.refillRatePerSecond();
    if (refillRate <= 0) {
        return limit.windowSeconds();
    }
    final var secondsUntilToken = 1.0 / refillRate;
    return Math.max(1, (long) Math.ceil(secondsUntilToken));
}
```

At 1.667 tokens/second, `Retry-After` is 1 second. At 1 token per 60 seconds, it is 60 seconds. The `Math.max(1, ...)` ensures we never tell a client to retry in 0 seconds.

**Trade-offs.** The token bucket is the best general-purpose algorithm for an API gateway because it handles bursty traffic gracefully. A mobile app that sends 10 requests in quick succession at startup and then goes quiet for a minute should not be penalized. The bucket absorbs the burst and refills during the quiet period. The downside is that burst capacity must be tuned. Set it too high and you effectively have no rate limit during bursts. Set it too low and legitimate usage patterns trigger 429s. The default of 100 (matching `defaultRequestsPerWindow`) is a reasonable starting point.

## 3. Algorithm Comparison

Although only the token bucket is currently implemented, the enum and interface are designed for all three. Here is how they compare:

| Characteristic | Token Bucket | Fixed Window | Sliding Window |
|---|---|---|---|
| **Burst handling** | Allows controlled bursts up to bucket capacity | No burst control; limit is per window | Smooths bursts across boundaries |
| **Boundary behavior** | No boundary issues; tokens refill continuously | Can allow 2x the limit at window boundaries (last second of one window + first second of next) | Weighted average eliminates boundary spikes |
| **Memory per key** | 2 values (tokens, lastRefillMillis) | 2 values (count, windowStart) | 3-4 values (current count, previous count, current window start, previous window start) |
| **Computational complexity** | O(1) per check | O(1) per check | O(1) per check (weighted average, not per-request timestamps) |
| **Best for** | General-purpose API rate limiting; traffic with natural bursts | Strict per-window accounting; billing or quota enforcement | Even rate enforcement; public APIs where boundary abuse is a concern |
| **Predictability for clients** | Less predictable (remaining tokens depend on refill timing) | Highly predictable (clear window boundaries) | Moderately predictable (smooth but harder to reason about) |
| **Implementation status** | Implemented (`BucketAlgorithm.java`) | Planned (enum defined, handler not registered) | Planned (enum defined, handler not registered) |

The fixed window's boundary problem is worth understanding concretely. If the limit is 100 requests per 60 seconds and the window resets at :00, a client can send 100 requests at :59 and 100 more at :01 (200 requests in 2 seconds). The sliding window solves this by weighting the previous window's count: if the current position is 20% into the new window, the effective count is `(current_count) + (previous_count * 0.8)`. This prevents the boundary spike at the cost of slightly more state.

## 4. Pre-Authentication Client Identification

A rate limiter is only as good as its identity boundary. Aussie's admission filter runs before authentication, so it uses only the canonical network identity implemented in `RateLimitFilter.extractClientId` (`api/src/main/java/aussie/system/filter/RateLimitFilter.java`):

```java
private String extractClientId(ContainerRequestContext requestContext, HttpServerRequest request) {
    return "ip:" + clientContextResolver.getOrCompute(requestContext, request).resolvedIp();
}
```

Session cookies, bearer tokens, and API-key identifiers are still caller-controlled bytes at this point. Letting any of them select the bucket would allow an attacker to rotate a header on every request and bypass the admission limit. Verified-principal quotas belong after authentication and supplement rather than replace this network bucket.

### Canonical client IP

The IP layer is delegated to `ClientContextResolver` (`api/src/main/java/aussie/adapter/in/context/ClientContextResolver.java`), which resolves the client IP exactly once per request and shares the result through both the Vert.x and JAX-RS request contexts. `ClientContextFilter` runs at priority `Priorities.AUTHENTICATION - 150` (850), ahead of `AuthRateLimitFilter` (900) and `RateLimitFilter` (950), so by the time either rate-limit filter executes the resolved IP is already memoised. Subsequent filters call:

```java
clientContextResolver.getOrCompute(requestContext, vertxRequest).resolvedIp();
```

The resolver first verifies that the socket peer is a configured proxy. It caps forwarding metadata at 8 KiB and 16 hops, accepts only valid IP nodes, then walks the chain from right to left past trusted proxy hops. The first untrusted address is the effective client, so a spoofed leftmost entry is ignored. Untrusted peers and malformed chains fall back to the socket address, with `"unknown"` as the final sentinel.

**Why this phase boundary matters.** A corporate NAT may place many users in one admission bucket, but that is an explicit availability trade-off. A post-authentication quota can distinguish verified users; it cannot erase the shared network protection.

**What a senior engineer might do differently.** Some gateways let service teams define custom pre-authentication identity headers. That flexibility is unsafe unless an authenticated infrastructure component creates and protects the header. Aussie keeps the pre-authentication namespace fixed and reserves principal-aware quotas for verified identities.

## 5. Platform Maximum Ceiling: Preventing Dangerous Configurations

The `EffectiveRateLimit` record at `api/src/main/java/aussie/core/model/ratelimit/EffectiveRateLimit.java` (line 56) enforces a platform maximum:

```java
public EffectiveRateLimit capAtPlatformMax(long platformMax) {
    if (requestsPerWindow <= platformMax && burstCapacity <= platformMax) {
        return this;
    }
    return new EffectiveRateLimit(
            Math.min(requestsPerWindow, platformMax),
            windowSeconds,
            Math.min(burstCapacity, platformMax));
}
```

This method is called at the end of every resolution path in `RateLimitResolver` (line 270 of `api/src/main/java/aussie/core/service/ratelimit/RateLimitResolver.java`):

```java
return new EffectiveRateLimit(requestsPerWindow, windowSeconds, burstCapacity)
        .capAtPlatformMax(config.platformMaxRequestsPerWindow());
```

### The Resolution Hierarchy

The `resolveLimit` method at line 241 of `RateLimitResolver.java` implements a three-tier override system:

```java
private EffectiveRateLimit resolveLimit(
        Optional<EndpointRateLimitConfig> endpointConfig,
        Optional<ServiceRateLimitConfig> serviceConfig,
        long defaultRequests,
        long defaultWindow,
        long defaultBurst) {

    // Start with platform defaults
    long requestsPerWindow = defaultRequests;
    long windowSeconds = defaultWindow;
    long burstCapacity = defaultBurst;

    // Apply service-level overrides
    if (serviceConfig.isPresent()) {
        final var svc = serviceConfig.get();
        requestsPerWindow = svc.requestsPerWindow().orElse(requestsPerWindow);
        windowSeconds = svc.windowSeconds().orElse(windowSeconds);
        burstCapacity = svc.burstCapacity().orElse(burstCapacity);
    }

    // Apply endpoint-level overrides (highest priority)
    if (endpointConfig.isPresent()) {
        final var ep = endpointConfig.get();
        requestsPerWindow = ep.requestsPerWindow().orElse(requestsPerWindow);
        windowSeconds = ep.windowSeconds().orElse(windowSeconds);
        burstCapacity = ep.burstCapacity().orElse(burstCapacity);
    }

    // Create and cap at platform maximum
    return new EffectiveRateLimit(requestsPerWindow, windowSeconds, burstCapacity)
            .capAtPlatformMax(config.platformMaxRequestsPerWindow());
}
```

The resolution order is: platform defaults, then service-level overrides, then endpoint-level overrides, and finally the platform maximum cap. Each level uses `Optional.orElse` to inherit from the level below when a value is not explicitly configured. This means a service team can override `requestsPerWindow` without touching `windowSeconds`, and the window duration falls through to the platform default.

The platform maximum (`AUSSIE_RATE_LIMITING_PLATFORM_MAX_REQUESTS_PER_WINDOW`) defaults to `Long.MAX_VALUE`; so, effectively unlimited. This is the right default for initial deployment. Platform teams can lower it once they understand traffic patterns. The config is at line 53 of `api/src/main/java/aussie/core/config/RateLimitingConfig.java`:

```java
@WithDefault("9223372036854775807")
long platformMaxRequestsPerWindow();
```

**Why this matters.** Without a platform maximum, a service team could register an endpoint with `requestsPerWindow: 1000000000` and effectively disable rate limiting for their service. In a shared gateway, that service's traffic could starve other services of gateway capacity. The platform maximum is a safety net: even if a service team makes a mistake, the damage is bounded.

**What a senior engineer might do differently.** Some systems validate rate limit configurations at registration time, rejecting values above the platform maximum rather than silently capping them. The capping approach in Aussie is more forgiving. A service team that sets a limit of 50,000 when the platform max is 10,000 will get 10,000 without an error. It also allows platform teams to adjusut the max up or down without forcing service teams to re-register their services. The trade-off is discoverability: the service team might not realize their configured value is not taking effect. A warning log or an admin API endpoint that shows effective limits would mitigate this.

## 6. WebSocket Rate Limiting: Two Dimensions of Control

HTTP rate limiting is straightforward: one request, one token consumed. WebSocket traffic requires two separate rate limiting dimensions because a single WebSocket connection can generate thousands of messages. The connection establishment and message throughput are fundamentally different resources.

### Connection Rate Limiting

The `WebSocketRateLimitFilter` at `api/src/main/java/aussie/adapter/in/websocket/WebSocketRateLimitFilter.java` (line 75) runs as a Vert.x `@RouteFilter` at priority 40:

```java
@RouteFilter(40)
void checkWebSocketRateLimit(RoutingContext ctx) {
    if (!isWebSocketUpgrade(ctx.request())) {
        ctx.next();
        return;
    }
    // ...
}
```

This filter intercepts WebSocket upgrade requests (identified by `Upgrade: websocket` and `Connection: upgrade` headers) and applies connection-level rate limits. The default is 10 connections per 60 seconds with a burst capacity of 5 (lines 148-166 of `RateLimitingConfig.java`). These are intentionally conservative because each WebSocket connection holds server resources (memory, file descriptors, upstream connections) for its entire lifetime.

The key is constructed with `RateLimitKey.wsConnection(clientId, serviceId)`, which produces the format `aussie:ratelimit:ws:conn:{serviceId}:{clientId}`. This is separate from the HTTP rate limit key namespace, so a client's HTTP traffic and WebSocket connections are rate-limited independently.

### Message Rate Limiting

Once a connection is established, message throughput is controlled by `WebSocketRateLimitService` at `api/src/main/java/aussie/core/service/ratelimit/WebSocketRateLimitService.java`:

```java
public Uni<RateLimitDecision> checkMessageLimit(
        String serviceId, String clientId, String connectionId) {
    if (!isMessageRateLimitEnabled()) {
        return Uni.createFrom().item(RateLimitDecision.allow());
    }

    final var key = RateLimitKey.wsMessage(clientId, serviceId, connectionId);

    return serviceRegistry.getServiceForRateLimiting(serviceId)
        .flatMap(service -> {
            final var limit = rateLimitResolver.resolveWebSocketMessageLimit(service);
            return rateLimiter.checkAndConsume(key, limit);
        });
}
```

The message key format is `aussie:ratelimit:ws:msg:{serviceId}:{clientId}:{connectionId}`. The `connectionId` is critical: it ensures each connection has its own message rate limit. Without it, a client with multiple connections would share a single message budget across all of them.

The defaults for message rate limiting are aggressive: 100 messages per 1 second with a burst capacity of 50. The 1-second window (line 195 of `RateLimitingConfig.java`) is much shorter than the 60-second window for HTTP traffic. This reflects the reality of WebSocket usage: message rates are measured in messages-per-second, not messages-per-minute.

When a message exceeds the rate limit, `WebSocketProxySession` closes the connection with code `4429` (line 259), which mirrors HTTP 429 in the WebSocket close code space (4000-4999 is the application-defined range). The `MessageRateLimitHandler` functional interface (line 12 of `api/src/main/java/aussie/core/service/ratelimit/MessageRateLimitHandler.java`) provides a callback-based API:

```java
@FunctionalInterface
public interface MessageRateLimitHandler {
    Uni<Void> checkAndProceed(Runnable onAllowed);
}
```

This design avoids blocking. The handler checks the rate limit reactively, and if allowed, invokes the `onAllowed` callback to forward the message to the upstream service.

### Connection Cleanup

When a WebSocket connection closes, the service cleans up its message rate limit state:

```java
public Uni<Void> cleanupConnection(
        String serviceId, String clientId, String connectionId) {
    return rateLimiter.reset(
        RateLimitKey.wsMessage(clientId, serviceId, connectionId));
}
```

This prevents unbounded growth of rate limit state from long-running applications that cycle through many connections over time.

**Why WebSocket needs different treatment.** An HTTP request is self-contained: it arrives, gets processed, and a response goes back. Rate limiting is a simple per-request check. A WebSocket connection is a long-lived bidirectional channel. You need to limit both the rate at which channels are created (to prevent resource exhaustion from connection floods) and the rate at which messages flow through each channel (to prevent a single chatty client from overwhelming the upstream service). These are independent dimensions with different time scales, different default values, and different key structures. Treating them as one dimension would either under-protect connections or over-restrict messages.

## 7. In-Memory vs. Redis Storage

### In-Memory: The Development Default

The `InMemoryRateLimiter` at `api/src/main/java/aussie/adapter/out/ratelimit/memory/InMemoryRateLimiter.java` stores state in a `ConcurrentHashMap`:

```java
private final ConcurrentMap<String, TimestampedState> states;
```

The `checkAndConsume` method (line 98) uses `ConcurrentHashMap.compute` for atomic read-modify-write:

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

The `compute` lambda is executed atomically with respect to the key: no other thread can modify the same key's state during execution. The single-element array `result[0]` is an idiomatic pattern for capturing a value from a lambda that returns a different type.

Each state is wrapped in a `TimestampedState` record (line 221) that tracks the last access time independently of the algorithm's internal timestamps:

```java
record TimestampedState(RateLimitState state, long lastAccessMillis) {}
```

### Stale Entry Cleanup

Without cleanup, the `ConcurrentHashMap` would grow without bound as new clients appear and old ones leave. The cleanup runs every 60 seconds on a daemon thread (line 93):

```java
cleanupExecutor.scheduleAtFixedRate(
    this::cleanupStaleEntries,
    CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
```

Entries are considered stale after 2x the window duration (line 189):

```java
private void cleanupStaleEntries() {
    final var now = clock.getAsLong();
    final var staleThreshold = now - (windowMillis * 2);
    final var sizeBefore = states.size();

    states.entrySet().removeIf(
        entry -> entry.getValue().lastAccessMillis() < staleThreshold);

    final var removed = sizeBefore - states.size();
    if (removed > 0) {
        LOG.debugf("Cleaned up %d stale rate limit entries, %d remaining",
            removed, states.size());
    }
}
```

The 2x multiplier is important. Using exactly the window duration would risk evicting entries that are still within their current window but happen to not have been accessed recently. The 2x buffer provides margin. This is staleness based on last access time (`lastAccessMillis` from the `TimestampedState` wrapper), not the algorithm's internal `lastRefillMillis`. This distinction matters because a token bucket's `lastRefillMillis` only updates when tokens are actually refilled, which might not happen on every request if the elapsed time is too short to produce even one token.

### Redis: The Production Backend

The `RedisRateLimiter` at `api/src/main/java/aussie/adapter/out/ratelimit/redis/RedisRateLimiter.java` implements the same `RateLimiter` interface but uses Lua scripts for atomicity. The token bucket Lua script (line 51) is the most critical piece of code in the entire rate limiting subsystem:

```lua
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local window_seconds = tonumber(ARGV[3])
local redis_time = redis.call('TIME')
local now_ms = (redis_time[1] * 1000) + math.floor(redis_time[2] / 1000)

-- Get current state
local data = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
local tokens = tonumber(data[1])
local last_refill_ms = tonumber(data[2])

-- Initialize if new key
if tokens == nil then
    tokens = capacity
    last_refill_ms = now_ms
end

-- Calculate token refill
local elapsed_ms = math.max(0, now_ms - last_refill_ms)
local refill = (elapsed_ms / 1000.0) * refill_rate
tokens = math.min(capacity, tokens + refill)

-- Check if request is allowed
local allowed = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
end

local tokens_needed = capacity - tokens
local seconds_to_full = refill_rate > 0
    and (tokens_needed / refill_rate) or window_seconds
local reset_at = math.floor(now_ms / 1000) + math.ceil(seconds_to_full)
local retry_after = allowed == 1 and 0 or (refill_rate > 0
    and math.max(1, math.ceil((1 - tokens) / refill_rate))
    or window_seconds)

-- Save state with TTL
redis.call('HSET', key, 'tokens', tokens, 'last_refill_ms', now_ms)
redis.call('EXPIRE', key, window_seconds * 2)

local remaining = math.floor(tokens)
local request_count = math.floor(capacity - tokens)
return {allowed, remaining, request_count, reset_at, retry_after}
```

**Why Lua scripts, not MULTI/EXEC.** A Redis transaction (`MULTI/EXEC`) does not allow reading a value and then making a decision based on it. Instead, all commands are queued and executed together without conditional logic. The token bucket requires reading current state, computing refill, checking token count, and conditionally decrementing. Lua scripts execute atomically on the Redis server with full access to conditional logic.

**Why Redis supplies the clock.** Every gateway instance evaluates elapsed time from Redis `TIME`, so host clock skew cannot grant or remove tokens. Negative elapsed time from pre-existing state is clamped to zero.

**Key expiration.** The `EXPIRE` call with `window_seconds * 2` mirrors the in-memory cleanup strategy. If a client disappears, their rate limit state will be automatically cleaned up by Redis after two window durations.

### Redis Failure Policy

`RedisRateLimiter` applies the configured `aussie.rate-limiting.fallback.behavior` when an operation fails:

- `DENY` (default) rejects the request with 429.
- `LOCAL_BUCKET` replaces every nonzero quota with a per-instance one-request, one-second limit; zero request or burst quotas remain zero.
- `ALLOW` preserves fail-open behavior for development or explicitly accepted risk.

### Provider Selection

The `RateLimiterProviderLoader` at `api/src/main/java/aussie/adapter/out/ratelimit/RateLimiterProviderLoader.java` (line 60) selects the backend at startup:

```java
@Produces
@ApplicationScoped
public RateLimiter produceRateLimiter() {
    if (!config.enabled()) {
        LOG.info("Rate limiting is disabled, using NoOpRateLimiter");
        return NoOpRateLimiter.getInstance();
    }
    final var rateLimiter = createRateLimiter();
    // ...
    return rateLimiter;
}
```

When Redis rate limiting is configured, failure to resolve or construct its provider fails startup rather than silently switching a cluster to independent in-memory quotas. Runtime Redis errors use the configured failure policy above. If Redis is disabled, the in-memory provider is used; if rate limiting itself is disabled, a `NoOpRateLimiter` allows everything.

**Trade-off: consistency vs. availability.** With in-memory rate limiting, each gateway instance maintains independent state. A client could send 100 requests to instance A (hitting the limit) and then send 100 more to instance B (which has no state for that client). In the worst case, the effective limit is `N * configured_limit` where N is the number of gateway instances. Redis eliminates this by sharing state. The trade-off is an additional network hop on every request. For most deployments, Redis latency (sub-millisecond on the same network) is negligible, and the consistency guarantee is worth it.

## 8. Filter Ordering: Rate Limiting Before Authentication

The `RateLimitFilter` at line 101 of `api/src/main/java/aussie/system/filter/RateLimitFilter.java` declares its priority explicitly:

```java
@ServerRequestFilter(priority = jakarta.ws.rs.Priorities.AUTHENTICATION - 50)
```

`jakarta.ws.rs.Priorities.AUTHENTICATION` is 1000. So the rate limit filter runs at priority 950, which is *before* authentication (lower numbers run first in JAX-RS). This means rate limit checks execute before any JWT validation, token introspection, or session lookup.

**Why this matters operationally.** Authentication is expensive. JWT validation involves signature verification (cryptographic operations). Token introspection requires an HTTP call to an identity provider. Session lookup requires a database or cache query. Rate limiting, by contrast, is cheap: one hash map lookup or one Redis call.

If an attacker sends 10,000 requests per second, you want to reject 9,900 of them (the ones exceeding the rate limit) *before* incurring the cost of authenticating each one. Running auth first would mean 10,000 JWT verifications per second, most of which are wasted because the request would be rejected by rate limiting anyway.

The WebSocket rate limit filter uses a different mechanism (Vert.x `@RouteFilter(40)`) because WebSocket upgrade requests are handled at the Vert.x layer, not the JAX-RS layer. In Vert.x route filters, higher numbers run first, so the actual execution order is route resolution (105), CORS (100), security headers (90), WebSocket upgrade (50), then WebSocket rate limiting (40). This means the rate limit filter runs *after* the upgrade handler, not before it. In practice, this is mitigated because `WebSocketUpgradeFilter` does not call `next()` for intercepted WebSocket requests, so `WebSocketRateLimitFilter` only runs for non-WebSocket traffic that passes through. See Appendix C for a detailed analysis of this ordering subtlety.

## 9. Response Headers and Telemetry

### Standard Rate Limit Headers

On allowed requests, the response filter at line 144 adds informational headers:

```java
@ServerResponseFilter
public void filterResponse(
        ContainerRequestContext requestContext,
        ContainerResponseContext responseContext) {
    if (!config().includeHeaders()) {
        return;
    }

    final var decision = (RateLimitDecision)
        requestContext.getProperty(RATE_LIMIT_DECISION_ATTR);
    if (decision != null && decision.allowed()) {
        responseContext.getHeaders().add("X-RateLimit-Limit", decision.limit());
        responseContext.getHeaders().add("X-RateLimit-Remaining", decision.remaining());
        responseContext.getHeaders().add("X-RateLimit-Reset", decision.resetAtEpochSeconds());
    }
}
```

On rejected requests, the 429 response at line 186 includes both the informational headers and `Retry-After`:

```java
private Response buildRateLimitResponse(RateLimitDecision decision) {
    final var detail = "Rate limit exceeded. Retry after %d seconds."
        .formatted(decision.retryAfterSeconds());

    return Response.status(429)
            .header("Retry-After", decision.retryAfterSeconds())
            .header("X-RateLimit-Limit", decision.limit())
            .header("X-RateLimit-Remaining", 0)
            .header("X-RateLimit-Reset", decision.resetAtEpochSeconds())
            .entity(GatewayProblem.tooManyRequests(
                    detail, decision.retryAfterSeconds(),
                    decision.limit(), 0, decision.resetAtEpochSeconds()))
            .build();
}
```

The response body uses RFC 9457 Problem Details format via `GatewayProblem.tooManyRequests`, which includes the rate limit metadata in a structured JSON body. This is friendlier than a bare 429 with only headers, as client libraries can parse the problem detail and extract the retry-after value programmatically.

The decision is passed from the request filter to the response filter via a request context property (`RATE_LIMIT_DECISION_ATTR` at line 63). This avoids recomputing the rate limit status during response processing. The property mechanism is JAX-RS standard--`requestContext.setProperty` in the request filter, `requestContext.getProperty` in the response filter--and is scoped to the current request.

Headers can be disabled entirely via `AUSSIE_RATE_LIMITING_INCLUDE_HEADERS=false`. Some organizations prefer not to expose rate limit internals to clients, particularly for public-facing APIs where the information could help attackers calibrate their request rates.

### Telemetry: StatusCode.OK, Not ERROR

The most counterintuitive decision in the telemetry code is at line 175:

```java
private void setExceededSpanAttributes(RateLimitDecision decision) {
    final var span = Span.current();
    telemetryHelper.setRateLimitRetryAfter(span, decision.retryAfterSeconds());
    // Use OK status - rate limiting is expected behavior, not an error
    // Errors would trigger alerts; rate limits are informational
    span.setStatus(StatusCode.OK, "Rate limit exceeded");
}
```

A rate-limited request returns HTTP 429, which looks like an error. But in OpenTelemetry, span status has a different purpose than HTTP status. Setting `StatusCode.ERROR` on a span triggers error-rate alerts, PagerDuty notifications, and dashboard anomaly detection. Rate limiting is *expected behavior*. It is the system working correctly, not failing. A spike in 429s means the rate limiter is protecting the system, not that something is broken.

The span attributes provide full observability without triggering false alarms:

```java
private void setSpanAttributes(RateLimitDecision decision) {
    final var span = Span.current();
    telemetryHelper.setRateLimited(span, !decision.allowed());
    telemetryHelper.setRateLimitRemaining(span, decision.remaining());
    telemetryHelper.setRateLimitType(span, SpanAttributes.RATE_LIMIT_TYPE_HTTP);
}
```

These attributes (`aussie.rate_limit.limited`, `aussie.rate_limit.remaining`, `aussie.rate_limit.type`) are queryable in trace exploration tools. You can find all rate-limited spans, all spans for a specific rate limit type, or all spans where `remaining` dropped below a threshold. The separate `RateLimitExceeded` security event (dispatched at line 180) feeds into the security monitoring pipeline for DDoS detection without polluting the general error budget.

The WebSocket rate limit filter at `api/src/main/java/aussie/adapter/in/websocket/WebSocketRateLimitFilter.java` (line 161) follows the same convention:

```java
span.setStatus(StatusCode.OK, "WebSocket connection rate limit exceeded");
```

**What a senior engineer might do differently.** Some systems use a custom span status or a separate metric dimension to distinguish "expected 4xx" from "unexpected 5xx." The `StatusCode.OK` approach is simpler and works with stock OpenTelemetry tooling, but it means you cannot filter "all non-OK spans" to find problems. Rate-limited requests will be invisible in that view. The dedicated `aussie.rate_limit.limited` attribute compensates for this by providing a direct query path.

## 10. Rate Limit Key Structure

The `RateLimitKey` record at `api/src/main/java/aussie/core/model/ratelimit/RateLimitKey.java` encodes the full context of a rate limit bucket into a cache key:

```java
public record RateLimitKey(
    RateLimitKeyType keyType,
    String clientId,
    String serviceId,
    Optional<String> endpointId) {
```

The `toCacheKey` method at line 74 produces different key formats for each type:

```java
public String toCacheKey() {
    return switch (keyType) {
        case HTTP -> buildHttpKey();
        case WS_CONNECTION -> buildWsConnectionKey();
        case WS_MESSAGE -> buildWsMessageKey();
    };
}
```

Resulting in:
- HTTP: `aussie:ratelimit:http:{serviceId}:{endpointId}:{clientId}`
- WebSocket connection: `aussie:ratelimit:ws:conn:{serviceId}:{clientId}`
- WebSocket message: `aussie:ratelimit:ws:msg:{serviceId}:{clientId}:{connectionId}`

The colon-separated namespace prefix (`aussie:ratelimit:`) follows Redis conventions and prevents collisions with other data stored in the same Redis instance. When no endpoint is matched, the HTTP key uses `*` as a wildcard: `aussie:ratelimit:http:{serviceId}:*:{clientId}`. This means unmatched requests share a single service-level bucket rather than each getting their own endpoint-level bucket.

Static factory methods (`http`, `wsConnection`, `wsMessage` at lines 42, 53, and 65) enforce correct construction:

```java
public static RateLimitKey http(String clientId, String serviceId, String endpointId) {
    return new RateLimitKey(
        RateLimitKeyType.HTTP, clientId, serviceId,
        Optional.ofNullable(endpointId));
}
```

The `endpointId` parameter is nullable (wrapped in `Optional.ofNullable`), but `clientId` and `serviceId` are validated as non-null in the compact constructor. This API design makes it impossible to construct a key with missing required fields, catching configuration bugs at construction time rather than at Redis lookup time.

## Summary

The rate limiting system in Aussie layers multiple concerns on top of a straightforward algorithm:

1. **Algorithm selection** is abstracted behind a strategy interface, making it possible to change algorithms without touching filter or storage code.
2. **Client identification** uses the canonical network identity before authentication, so unverified credentials cannot select a fresh bucket.
3. **The platform maximum** prevents service teams from configuring limits that could endanger the shared infrastructure, while the hierarchical resolution (endpoint > service > platform) gives service teams flexibility within that boundary.
4. **WebSocket traffic** is handled with two independent dimensions (connection rate and message rate) because the resource consumption model is fundamentally different from HTTP.
5. **Storage** is pluggable between in-memory and Redis, with an explicit failure policy and stale entry cleanup for the in-memory path.
6. **Filter ordering** ensures cheap rejection happens before expensive authentication.
7. **Telemetry** treats rate limiting as expected behavior (`StatusCode.OK`), not an error, preventing false alarms while maintaining full observability through custom span attributes.

The design is deliberately opinionated in areas where flexibility creates risk (client identification, platform maximums) and deliberately extensible in areas where requirements are likely to change (algorithm selection, storage backends). This balance between rigidity where safety matters and flexibility where operational needs evolve is the mark of infrastructure code that survives contact with production.
