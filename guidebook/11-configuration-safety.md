# Chapter 11: Configuration as a Safety System

Configuration in an API gateway is not a convenience feature. It is load-bearing infrastructure. A mistyped timeout brings down your entire request pipeline. A missing encryption key silently stores secrets in plaintext. A development flag left enabled in production disables authentication for every service behind the gateway.

Most senior engineers treat configuration as a secondary concern -- something to deal with after the "real" code ships. They scatter magic numbers through source files, pass raw strings around, and document the expected environment variables in a wiki that drifts out of date within weeks. The Aussie codebase takes a different position: configuration is a safety system, and like all safety systems, it must be type-safe, hierarchical, validated at startup, and documented exhaustively.

This chapter examines the patterns that make that position concrete.

---

## 1. @ConfigMapping Interfaces with Validation and Defaults

Quarkus provides `@ConfigMapping` as a way to bind configuration properties to Java interfaces at build time. Aussie uses this extensively, and the way it uses it reveals a design principle: every configurable value must have a declared type, a safe default, and a structural home.

### The Root Config Interface

The gateway's top-level configuration is a composition of nested interfaces:

```java
// api/src/main/java/aussie/adapter/out/http/GatewayConfig.java (lines 1-22)
@ConfigMapping(prefix = "aussie.gateway")
public interface GatewayConfig {

    ForwardingConfig forwarding();

    LimitsConfig limits();

    AccessControlConfig accessControl();

    SecurityConfig security();

    TrustedProxyConfig trustedProxy();
}
```

There are no fields, no constructors, no mutation methods. This is an interface, and Quarkus generates the implementation at build time by binding property keys to method names. The prefix `aussie.gateway` means that `forwarding().useRfc7239()` resolves to the property `aussie.gateway.forwarding.use-rfc7239`.

The nested structure is intentional. Each sub-interface groups a coherent set of concerns. `LimitsConfig` handles request size limits:

```java
// api/src/main/java/aussie/core/model/common/LimitsConfig.java (lines 1-24)
public interface LimitsConfig {

    @WithDefault("10485760")
    long maxBodySize();

    @WithDefault("8192")
    int maxHeaderSize();

    @WithDefault("32768")
    int maxTotalHeadersSize();
}
```

Every method has a `@WithDefault` annotation. This means you can deploy Aussie with zero configuration and get safe, reasonable limits: 10 MB body, 8 KB per header, 32 KB total headers. You do not need to read the documentation to avoid an OOM from an unbounded request body. The default protects you.

### Deep Hierarchical Config

The resiliency configuration demonstrates how this nesting scales to complex subsystems:

```java
// api/src/main/java/aussie/core/config/ResiliencyConfig.java (lines 21-198)
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
    // ...
}
```

Notice three things about this design.

First, the types are specific. Timeouts are `Duration`, not `String` or `long`. Connection counts are `int`, not `String`. Quarkus validates these at startup. If someone sets `AUSSIE_RESILIENCY_HTTP_REQUEST_TIMEOUT=banana`, the application refuses to start. You discover the mistake at deploy time, not when the first request times out in an unexpected way.

Second, the defaults encode operational knowledge. A 30-second request timeout, 5-second connect timeout, 50 connections per host. These are not arbitrary -- they represent reasonable starting points that will work for most deployments without tuning. The javadoc on each method explains the consequences: "If exceeded, returns 504 Gateway Timeout" for `requestTimeout()`, "Falls back to cached keys if available" for the JWKS `fetchTimeout()`.

Third, the timeout behavior is documented per-operation for Redis, right in the interface javadoc (lines 169-175):

```java
/**
 * <p>Behavior on timeout depends on operation type:
 * <ul>
 *   <li>Session operations: propagate error (critical)</li>
 *   <li>Cache reads: return empty (treat as cache miss)</li>
 *   <li>Rate limiting: allow request (fail-open)</li>
 *   <li>Token revocation: deny request (fail-closed for security)</li>
 * </ul>
 */
@WithDefault("PT1S")
Duration operationTimeout();
```

This is configuration documentation embedded in the configuration declaration. Someone reading the interface knows exactly what changing the Redis timeout does, and more importantly, what happens when the timeout is exceeded. Rate limiting fails open (let traffic through). Token revocation fails closed (block traffic). These are security decisions encoded in the configuration model, not buried in implementation code.

### What a Senior Might Do Instead

A common approach is to use `@ConfigProperty` on individual fields scattered across service classes:

```java
// Anti-pattern: scattered config
@ConfigProperty(name = "http.timeout", defaultValue = "30000")
long httpTimeoutMs;

@ConfigProperty(name = "redis.timeout", defaultValue = "1000")
long redisTimeoutMs;
```

This works for small applications. It collapses at scale. You cannot discover all configuration properties without searching every class. There is no structural relationship between properties. The property names are arbitrary strings with no enforced naming convention. And the types tend to drift toward primitives -- `long` milliseconds instead of `Duration` -- losing the type safety that prevents a class of operational errors.

### Trade-offs

The `@ConfigMapping` approach trades startup speed for runtime safety. Quarkus validates the entire configuration tree at build time, which means compilation takes slightly longer and the application fails loudly if any property is malformed. This is the correct trade-off for infrastructure software. The alternative -- discovering bad configuration when a request exercises a particular code path at 3 AM -- is strictly worse.

The nesting can become deep. `aussie.resiliency.cassandra.max-requests-per-connection` maps to the environment variable `AUSSIE_RESILIENCY_CASSANDRA_MAX_REQUESTS_PER_CONNECTION`. This is verbose. It is also unambiguous, which matters more.

---

## 2. Hierarchical Configuration Resolution

Rate limiting in a multi-tenant API gateway requires layered configuration. The platform team sets global policy. Service teams customize within those boundaries. Specific endpoints can be tuned further. The `RateLimitResolver` implements this hierarchy with a strict invariant: no configuration at any level can exceed the platform maximum.

### The Resolution Hierarchy

The resolver follows a three-tier priority system:

```java
// api/src/main/java/aussie/core/service/ratelimit/RateLimitResolver.java (lines 241-272)
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

The resolution works by starting with platform defaults (from `RateLimitingConfig`), then selectively replacing values where service or endpoint configuration exists. The key insight is in the `Optional` pattern used by `ServiceRateLimitConfig` and `EndpointRateLimitConfig`:

```java
// api/src/main/java/aussie/core/model/ratelimit/ServiceRateLimitConfig.java (lines 18-22)
public record ServiceRateLimitConfig(
        Optional<Long> requestsPerWindow,
        Optional<Long> windowSeconds,
        Optional<Long> burstCapacity,
        Optional<ServiceWebSocketRateLimitConfig> websocket) {
```

Every field is `Optional`. When a service team registers their service with `requestsPerWindow = Optional.of(500)` but leaves `windowSeconds` empty, they get 500 requests per the platform-default 60-second window. This partial override model means service teams only configure what they need to change.

### The Platform Maximum Ceiling

The final line of every resolution path calls `capAtPlatformMax`:

```java
// api/src/main/java/aussie/core/model/ratelimit/EffectiveRateLimit.java (lines 56-62)
public EffectiveRateLimit capAtPlatformMax(long platformMax) {
    if (requestsPerWindow <= platformMax && burstCapacity <= platformMax) {
        return this;
    }
    return new EffectiveRateLimit(
            Math.min(requestsPerWindow, platformMax), windowSeconds, Math.min(burstCapacity, platformMax));
}
```

This is the enforcement mechanism. The platform team sets `platformMaxRequestsPerWindow` via the `AUSSIE_RATE_LIMITING_PLATFORM_MAX_REQUESTS_PER_WINDOW` environment variable. By default, it is `Long.MAX_VALUE` (effectively unlimited):

```java
// api/src/main/java/aussie/core/config/RateLimitingConfig.java (lines 47-54)
/**
 * <p><b>Platform teams only.</b> Service and endpoint limits cannot exceed
 * this value. Set to a generous value; defaults to effectively unlimited.
 */
@WithDefault("9223372036854775807")
long platformMaxRequestsPerWindow();
```

The javadoc is explicit: "Platform teams only. Service and endpoint limits cannot exceed this value." Even if a service team registers an endpoint with `requestsPerWindow = 1_000_000`, and the platform max is `10_000`, the effective limit is clamped to `10_000`. The platform team never needs to audit individual service configurations to enforce their policy. The ceiling is structural.

### Caching for Multi-Instance Consistency

The resolver caches service configurations with TTL-based expiration:

```java
// api/src/main/java/aussie/core/service/ratelimit/RateLimitResolver.java (lines 50-56)
@Inject
public RateLimitResolver(RateLimitingConfig config, ServiceRegistry serviceRegistry, LocalCacheConfig cacheConfig) {
    this.config = config;
    this.serviceRegistry = serviceRegistry;
    this.serviceConfigCache = new CaffeineLocalCache<>(
            cacheConfig.rateLimitConfigTtl(), cacheConfig.maxEntries(), cacheConfig.jitterFactor());
}
```

The cache TTL comes from `LocalCacheConfig` (default 30 seconds), and it includes jitter to prevent all instances from refreshing simultaneously. When a service configuration is updated on one instance, other instances will see the change within the TTL window -- eventual consistency that avoids thundering herd problems.

### What a Senior Might Do Instead

A simpler approach is flat configuration: every service gets the same rate limits, configured once at the platform level. This avoids the resolution complexity but forces service teams into one-size-fits-all limits. A high-throughput data pipeline and a low-volume admin API get the same rate limit, which means either the pipeline is over-constrained or the admin API is under-protected.

Another common pattern is letting service teams set their own limits with no ceiling. This works until a service team sets their limit to `Integer.MAX_VALUE` because they "don't want rate limiting" and the absence of a platform-enforced cap means a single compromised or misbehaving service can starve all others.

### Trade-offs

The hierarchical model is more complex to understand. A platform operator debugging a 429 response needs to trace through endpoint config, service config, and platform defaults to understand the effective limit. The resolver helps by producing an `EffectiveRateLimit` record that represents the final computed values, but the resolution itself is multi-step.

The `Optional` fields in `ServiceRateLimitConfig` and `EndpointRateLimitConfig` mean that deserialization requires explicit null-handling in the compact constructors (lines 24-29 of `ServiceRateLimitConfig`). This is additional boilerplate, but it prevents a class of NPEs that would otherwise surface at runtime when a partially-configured service makes its first request.

---

## 3. Instance<Config> for Optional Features

Not every Aussie deployment enables every feature. Some deployments run without CORS. Some run without security headers. Some run without sessions. CDI's `Instance<T>` provides a clean way to handle features that may or may not be configured, without null checks scattered through business logic.

### The Pattern

The `CorsFilter` demonstrates the canonical usage:

```java
// api/src/main/java/aussie/adapter/in/http/CorsFilter.java (lines 41-59)
private final Instance<GatewayCorsConfig> corsConfigInstance;

@Inject
public CorsFilter(Instance<GatewayCorsConfig> corsConfigInstance) {
    this.corsConfigInstance = corsConfigInstance;
}

@RouteFilter(100)
void corsHandler(RoutingContext rc) {
    if (!corsConfigInstance.isResolvable()) {
        LOG.debug("CORS config not resolvable, skipping");
        rc.next();
        return;
    }

    GatewayCorsConfig globalConfig = corsConfigInstance.get();
    if (!globalConfig.enabled()) {
        LOG.debug("CORS is disabled, skipping");
        rc.next();
        return;
    }
    // ... CORS handling logic
}
```

The filter injects `Instance<GatewayCorsConfig>` rather than `GatewayCorsConfig` directly. The `isResolvable()` check on line 55 asks the CDI container whether a bean of that type exists. If the configuration mapping is not present -- because the relevant properties are not defined -- the filter silently skips CORS processing and passes control to the next filter.

The `SecurityHeadersFilter` uses the identical pattern:

```java
// api/src/main/java/aussie/adapter/in/http/SecurityHeadersFilter.java (lines 26-44)
private final Instance<SecurityHeadersConfig> configInstance;

@Inject
public SecurityHeadersFilter(Instance<SecurityHeadersConfig> configInstance) {
    this.configInstance = configInstance;
}

@RouteFilter(90)
void addSecurityHeaders(RoutingContext rc) {
    if (!configInstance.isResolvable()) {
        LOG.debug("Security headers config not resolvable, skipping");
        rc.next();
        return;
    }
    // ...
}
```

And the `RateLimitFilter`:

```java
// api/src/main/java/aussie/system/filter/RateLimitFilter.java (lines 67, 92-94)
private final Instance<RateLimitingConfig> configInstance;

private RateLimitingConfig config() {
    return configInstance.get();
}
```

### Why This Is Better Than Null Checks

The alternative is injecting the config directly and handling nulls:

```java
// Anti-pattern: direct injection with null check
@Inject
GatewayCorsConfig corsConfig; // might be null if not configured

void handle(RoutingContext rc) {
    if (corsConfig == null) { ... }
}
```

This has two problems. First, CDI will throw an `UnsatisfiedResolutionException` at startup if the bean is not available, unless you mark the injection point as `@Nullable` or use `Optional`. Second, null checks are viral -- every code path that touches the config must remember to check. `Instance<T>` moves the existence check to a single, explicit location and makes the feature's optionality visible in the type signature.

### The Two-Phase Check

Notice that every usage performs two checks: `isResolvable()` and then `enabled()`. The first asks "is this feature configured at all?" The second asks "is this feature turned on?" This separation matters because `@ConfigMapping` interfaces with `@WithDefault` annotations will always be resolvable if any property in their prefix is defined. The `enabled()` flag provides an explicit runtime toggle independent of whether the configuration mapping exists.

### What a Senior Might Do Instead

Inject `Optional<Config>` instead of `Instance<Config>`. This is a reasonable alternative for standard CDI beans, but `Instance<T>` provides additional capabilities: `isResolvable()` for existence checks without eagerly creating the bean, and `isAmbiguous()` for detecting multiple implementations. For configuration objects that are cheap to create, `Optional` works fine. For heavier beans where lazy resolution matters, `Instance<T>` is more appropriate.

### Trade-offs

`Instance<T>` is slightly more verbose than direct injection. Every access requires either `configInstance.get()` (after an `isResolvable()` check) or storing the result in a local variable. The `RateLimitFilter` wraps this in a `config()` method to reduce noise. The verbosity is the cost of making optionality explicit rather than implicit.

---

## 4. Production Profile Guards

The most dangerous configuration errors are the ones that succeed silently. An authentication system that disables itself without error is worse than one that crashes, because the crash is visible and the silent disabling is not. Aussie addresses this with startup guards that fail the application before it serves a single request.

### The NoopAuthGuard

The centerpiece is `NoopAuthGuard`, which prevents the `dangerous-noop` authentication mode from running in production:

```java
// api/src/main/java/aussie/adapter/in/auth/NoopAuthGuard.java (lines 17-50)
@ApplicationScoped
public class NoopAuthGuard {

    private static final Logger LOG = Logger.getLogger(NoopAuthGuard.class);

    void onStart(@Observes StartupEvent event) {
        if (isDangerousNoopEnabled() && currentLaunchMode() == LaunchMode.NORMAL) {
            LOG.error("aussie.auth.dangerous-noop=true is not allowed in production mode");
            throw new IllegalStateException(
                "aussie.auth.dangerous-noop=true is not allowed in production mode. "
                + "This setting disables all authentication. "
                + "Remove this setting or run in dev/test mode.");
        }
    }

    boolean isDangerousNoopEnabled() {
        return ConfigProvider.getConfig()
                .getOptionalValue("aussie.auth.dangerous-noop", Boolean.class)
                .orElse(false);
    }

    LaunchMode currentLaunchMode() {
        return LaunchMode.current();
    }
}
```

The guard observes `StartupEvent`, which fires after CDI initialization but before the application starts accepting requests. It checks two conditions: is the dangerous-noop flag enabled, and is this a production deployment (`LaunchMode.NORMAL`)? If both are true, it throws `IllegalStateException`, which aborts startup.

The error message is specific and actionable: it names the property, explains what it does ("disables all authentication"), and tells you what to do about it ("remove this setting or run in dev/test mode"). This is not a generic "invalid configuration" error. It is a security guard with a clear explanation.

### Why Deploy-Time Failure Beats Runtime Surprise

Consider the alternative: the noop auth mode silently enables itself in production. Authentication is disabled for every service behind the gateway. No request is rejected. No alarm fires. The vulnerability exists from the moment of deployment until someone notices -- which might be hours, days, or never until an attacker finds it.

The startup guard converts this to a deployment failure. The pod crashes, the health check fails, the rollout is halted. The operator sees a clear error in the logs. The fix is immediate: remove the flag. The window of vulnerability is zero because the application never starts serving traffic.

### Testing the Guard

The test suite verifies all three scenarios:

```java
// api/src/test/java/aussie/adapter/in/auth/NoopAuthGuardTest.java (lines 14-76)
@DisplayName("NoopAuthGuard")
class NoopAuthGuardTest {

    private NoopAuthGuard guardWith(boolean noopEnabled, LaunchMode mode) {
        return new NoopAuthGuard() {
            @Override
            boolean isDangerousNoopEnabled() { return noopEnabled; }

            @Override
            LaunchMode currentLaunchMode() { return mode; }
        };
    }

    @Nested
    @DisplayName("when dangerous-noop is enabled in production")
    class WhenEnabledInProduction {
        @Test
        @DisplayName("should throw IllegalStateException")
        void shouldThrowInProductionMode() {
            var guard = guardWith(true, LaunchMode.NORMAL);
            var ex = assertThrows(IllegalStateException.class, () -> guard.onStart(event));
            assertTrue(ex.getMessage().contains("not allowed in production mode"));
        }
    }

    @Nested
    @DisplayName("when dangerous-noop is enabled in non-production")
    class WhenEnabledInNonProduction {
        @Test
        @DisplayName("should allow startup in test mode")
        void shouldAllowTestMode() {
            var guard = guardWith(true, LaunchMode.TEST);
            assertDoesNotThrow(() -> guard.onStart(event));
        }

        @Test
        @DisplayName("should allow startup in dev mode")
        void shouldAllowDevMode() {
            var guard = guardWith(true, LaunchMode.DEVELOPMENT);
            assertDoesNotThrow(() -> guard.onStart(event));
        }
    }
}
```

The test design is worth noting. The guard's `isDangerousNoopEnabled()` and `currentLaunchMode()` methods are package-private and overridable, specifically to enable this testing pattern. The guard reads config dynamically via `ConfigProvider.getConfig()` rather than injecting the value, which allows test profiles to override the value without modifying the CDI container. This is a deliberate design choice documented in the source: "Reads the config dynamically at runtime. This ensures test profiles can override the value."

### The Naming Convention

The property is called `aussie.auth.dangerous-noop`, not `aussie.auth.disable-auth` or `aussie.auth.noop`. The word "dangerous" is load-bearing. It forces anyone writing the property to acknowledge that they are doing something dangerous. The application.properties default and comment reinforce this:

```properties
# api/src/main/resources/application.properties (lines 193-195)
# DANGEROUS: Disable authentication entirely for development
# WARNING: Application will refuse to start if enabled in production mode.
aussie.auth.dangerous-noop=false
```

### What a Senior Might Do Instead

A common approach is a simple boolean `auth.enabled` flag with no production guard. This is less friction in development -- you just set `auth.enabled=false` -- but it creates two problems. First, the property name does not communicate danger. Second, there is no structural protection against the flag being set in production. You rely entirely on process (code review, deployment checklists) to prevent the mistake. Process fails. Code does not.

Another approach is to not provide a noop mode at all. This is safer but makes local development significantly harder, requiring real authentication infrastructure for every developer environment.

### Trade-offs

The guard requires Quarkus-specific APIs (`LaunchMode`, `StartupEvent`), which couples the safety check to the framework. In a framework-agnostic design, you would need an equivalent mechanism (Spring's `ApplicationReadyEvent`, Micronaut's `@EventListener`, etc.). The coupling is acceptable because configuration safety must be framework-aware to know whether the deployment is production or development.

---

## 5. Environment Variable Naming Conventions

Quarkus maps configuration property names to environment variables using a deterministic convention: dots become underscores, hyphens become underscores, and everything is uppercased. The property `aussie.rate-limiting.platform-max-requests-per-window` becomes `AUSSIE_RATE_LIMITING_PLATFORM_MAX_REQUESTS_PER_WINDOW`.

This is not optional or configurable. It is a framework convention that every Quarkus application follows. Aussie's documentation makes this convention explicit with exhaustive tables in the platform README.

### The Documentation Standard

The platform documentation (`docs/platform/README.md`, lines 865-1010) provides comprehensive tables for every configurable subsystem. Here is the rate limiting section:

```
| Variable                                           | Default  | Description                                          |
|----------------------------------------------------|----------|------------------------------------------------------|
| AUSSIE_RATE_LIMITING_ENABLED                       | true     | Enable rate limiting                                 |
| AUSSIE_RATE_LIMITING_ALGORITHM                     | BUCKET   | Algorithm: BUCKET, FIXED_WINDOW, SLIDING_WINDOW      |
| AUSSIE_RATE_LIMITING_DEFAULT_REQUESTS_PER_WINDOW   | 100      | Default requests per window                          |
| AUSSIE_RATE_LIMITING_WINDOW_SECONDS                | 60       | Window duration in seconds                           |
```

And the resiliency section:

```
| Variable                                                  | Default | Description                                              |
|-----------------------------------------------------------|---------|----------------------------------------------------------|
| AUSSIE_RESILIENCY_HTTP_CONNECT_TIMEOUT                    | PT5S    | Max time to establish connection to upstream service      |
| AUSSIE_RESILIENCY_HTTP_REQUEST_TIMEOUT                    | PT30S   | Max time to wait for response (returns 504 if exceeded)  |
| AUSSIE_RESILIENCY_CASSANDRA_QUERY_TIMEOUT                 | PT5S    | Max time to wait for Cassandra queries                   |
| AUSSIE_RESILIENCY_CASSANDRA_POOL_LOCAL_SIZE               | 30      | Connections per node in local datacenter                 |
| AUSSIE_RESILIENCY_CASSANDRA_MAX_REQUESTS_PER_CONNECTION   | 1024    | Max concurrent requests per Cassandra connection         |
| AUSSIE_RESILIENCY_REDIS_OPERATION_TIMEOUT                 | PT1S    | Max time to wait for Redis operations                    |
| AUSSIE_RESILIENCY_REDIS_POOL_SIZE                         | 30      | Max Redis connections in pool                            |
| AUSSIE_RESILIENCY_REDIS_POOL_WAITING                      | 100     | Max requests waiting when Redis pool exhausted           |
```

Every entry includes the variable name, the default value, and a one-line description. The description is not generic -- "Max time to wait for response (returns 504 if exceeded)" tells you both the purpose and the failure mode.

### Config-to-Env-Var Traceability

The config interfaces themselves document the environment variable mapping. The `RateLimitingConfig` interface includes an explicit javadoc block:

```java
// api/src/main/java/aussie/core/config/RateLimitingConfig.java (lines 14-21)
/**
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code AUSSIE_RATE_LIMITING_ENABLED} - Enable/disable rate limiting</li>
 *   <li>{@code AUSSIE_RATE_LIMITING_ALGORITHM} - Algorithm: BUCKET, FIXED_WINDOW, SLIDING_WINDOW</li>
 *   <li>{@code AUSSIE_RATE_LIMITING_PLATFORM_MAX_REQUESTS_PER_WINDOW} - Maximum ceiling</li>
 * </ul>
 */
```

And `LocalCacheConfig` does the same:

```java
// api/src/main/java/aussie/core/cache/LocalCacheConfig.java (lines 26-32)
/**
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code AUSSIE_CACHE_LOCAL_SERVICE_ROUTES_TTL} - e.g., "PT30S" for 30 seconds</li>
 *   <li>{@code AUSSIE_CACHE_LOCAL_RATE_LIMIT_CONFIG_TTL} - e.g., "PT30S" for 30 seconds</li>
 *   <li>{@code AUSSIE_CACHE_LOCAL_MAX_ENTRIES} - e.g., "10000"</li>
 *   <li>{@code AUSSIE_CACHE_LOCAL_JITTER_FACTOR} - e.g., "0.1" for +/-10% jitter</li>
 * </ul>
 */
```

This dual documentation -- interface javadoc plus platform README tables -- creates two paths to the same information. A developer reading the source finds the env var name in the interface. An operator reading the platform docs finds it in the table. Neither path requires visiting the other.

### What a Senior Might Do Instead

Rely on the Quarkus convention alone, without documenting the mapped env var names. This works if every operator understands the mapping rules. In practice, operators copying environment variables from Kubernetes manifests or Terraform templates do not want to mentally apply string transformation rules. They want the exact variable name, and they want it in one place.

### Trade-offs

Maintaining documentation in two places (interface javadoc and platform README) creates a synchronization risk. When a property is added to the interface but not the README, the operator documentation is incomplete. This can be partially mitigated with automated documentation generation, but Aussie currently relies on manual maintenance. The benefit of having the information where each audience looks for it outweighs the maintenance cost, as long as the team treats documentation updates as part of the feature definition of done.

---

## 6. Sensitive Configuration

Some configuration values are secrets: encryption keys, signing keys, database credentials. Aussie draws a hard line between configuration that can be committed to version control and configuration that must be injected at runtime from a secrets manager.

### The Secrets Inventory

The `production-secrets.md` document (`docs/platform/production-secrets.md`, lines 7-14) maintains an explicit inventory:

```
| Secret               | Env Variable           | Format                  | Purpose                          | Rotation Cadence |
|----------------------|------------------------|-------------------------|----------------------------------|------------------|
| JWS Signing Key      | AUSSIE_JWS_SIGNING_KEY | RSA PKCS#8 PEM          | Signs session JWS tokens         | Quarterly        |
| Bootstrap Key        | AUSSIE_BOOTSTRAP_KEY   | String (min 32 chars)   | First-time admin setup           | Single-use       |
| Encryption Key       | AUTH_ENCRYPTION_KEY    | Base64-encoded 256-bit  | Encrypts API key records at rest | Annually         |
| Cassandra Credentials| CASSANDRA_USERNAME/PW  | String                  | Database authentication          | Per org policy   |
| Redis Password       | REDIS_PASSWORD         | String                  | Redis authentication             | Per org policy   |
| OIDC Client Secret   | OIDC_CLIENT_SECRET     | String                  | OAuth2 client authentication     | Per IdP policy   |
```

Each secret has a format specification, a purpose, and a rotation cadence. This is not merely documentation -- it is an operational contract. The platform team knows exactly which values need to come from a secrets manager, what format they need to be in, and how often they need to rotate.

### Graceful Degradation for Missing Encryption Keys

The `ApiKeyEncryptionService` demonstrates how Aussie handles a missing encryption key:

```java
// api/src/main/java/aussie/core/service/auth/ApiKeyEncryptionService.java (lines 55-76)
@Inject
public ApiKeyEncryptionService(
        @ConfigProperty(name = "aussie.auth.encryption.key") Optional<String> encryptionKey,
        @ConfigProperty(name = "aussie.auth.encryption.key-id", defaultValue = "v1") String keyId) {
    this.keyId = keyId;
    this.secureRandom = new SecureRandom();

    if (encryptionKey.isPresent() && !encryptionKey.get().isBlank()) {
        byte[] keyBytes = Base64.getDecoder().decode(encryptionKey.get());
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "Encryption key must be 256 bits (32 bytes). Got: " + keyBytes.length + " bytes");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        this.encryptionEnabled = true;
        LOG.info("API key encryption enabled with key ID: " + keyId);
    } else {
        this.secretKey = null;
        this.encryptionEnabled = false;
        LOG.warn("API key encryption is DISABLED. Set aussie.auth.encryption.key to enable.");
    }
}
```

When the encryption key is missing, the service does not crash. It logs a warning and falls back to plaintext storage with a `PLAIN:` prefix marker:

```java
// api/src/main/java/aussie/core/service/auth/ApiKeyEncryptionService.java (lines 86-91)
public String encrypt(ApiKey apiKey) {
    String serialized = serialize(apiKey);

    if (!encryptionEnabled) {
        // Return plaintext marker + serialized data
        return "PLAIN:" + Base64.getEncoder().encodeToString(serialized.getBytes(StandardCharsets.UTF_8));
    }
    // ... AES-256-GCM encryption
}
```

The `PLAIN:` prefix is critical. When the `decrypt()` method encounters data starting with `PLAIN:`, it knows the data was stored without encryption. When it encounters data without the prefix, it knows encryption is required. This allows a deployment to start without an encryption key (for development or initial setup) and later migrate to encrypted storage without losing access to existing data.

However, if encryption is disabled and the service encounters encrypted data, it throws immediately:

```java
// api/src/main/java/aussie/core/service/auth/ApiKeyEncryptionService.java (lines 124-134)
public ApiKey decrypt(String encryptedData) {
    if (encryptedData.startsWith("PLAIN:")) {
        String encoded = encryptedData.substring(6);
        String serialized = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        return deserialize(serialized);
    }

    if (!encryptionEnabled) {
        throw new IllegalStateException(
            "Cannot decrypt data: encryption is disabled but data appears encrypted");
    }
    // ...
}
```

This is a unidirectional safety net. You can go from plaintext to encrypted storage by adding a key. You cannot go from encrypted to plaintext by removing a key -- the application will fail rather than silently lose access to existing encrypted records.

### Key Validation at Startup

When an encryption key is provided, it is validated immediately in the constructor: it must be exactly 32 bytes (256 bits) after Base64 decoding. If the key is the wrong size, the application throws `IllegalArgumentException` and refuses to start. You discover the mistake at deploy time, not when the first API key creation fails at 3 AM.

### What a Senior Might Do Instead

A common approach is to require the encryption key unconditionally:

```java
// Stricter but less flexible
@ConfigProperty(name = "encryption.key")
String encryptionKey; // CDI throws if missing
```

This is simpler and arguably safer for production -- there is no plaintext fallback path. But it makes development and testing harder, because every environment needs a key. Aussie's approach allows zero-config development while still protecting against silent misconfiguration through the `PLAIN:` marker and the explicit warning log.

### Trade-offs

The plaintext fallback means that a production deployment without an encryption key stores API key records in readable form. The warning log is the only signal. If logs are not monitored, this can go undetected. A stricter approach would fail startup in production mode (like the NoopAuthGuard pattern), but Aussie chose flexibility here -- partly because encryption is a defense-in-depth measure for data at rest, and the primary security control (hashing the actual API key) remains active regardless.

---

## 7. Configuration Documentation

Aussie maintains a convention that every configurable property is documented in at least two places: the interface javadoc and the platform README. The token revocation configuration illustrates the depth of this documentation.

### Interface-Level Documentation

`TokenRevocationConfig` documents not just what each property does, but the engineering context for choosing a value:

```java
// api/src/main/java/aussie/core/config/TokenRevocationConfig.java (lines 88-114)
interface BloomFilterConfig {

    @WithDefault("true")
    boolean enabled();

    /**
     * <p>Size the bloom filter appropriately for your workload:
     * <ul>
     *   <li>Small (<1K RPS): 100,000 (~1.2 MB)</li>
     *   <li>Medium (1-10K RPS): 1,000,000 (~12 MB)</li>
     *   <li>Large (>10K RPS): 10,000,000 (~120 MB)</li>
     * </ul>
     */
    @WithDefault("100000")
    int expectedInsertions();

    /**
     * <p>Trade-offs:
     * <ul>
     *   <li>0.1% (0.001): ~10 bits/element, minimal false lookups</li>
     *   <li>1% (0.01): ~7 bits/element, 10x more false lookups but 30% less memory</li>
     *   <li>0.01% (0.0001): ~13 bits/element, negligible false lookups but 30% more memory</li>
     * </ul>
     */
    @WithDefault("0.001")
    double falsePositiveProbability();
}
```

This is not documenting what the property is -- it is documenting how to choose a value. The sizing guidance ties RPS (requests per second) to memory consumption. The false positive probability documentation shows the memory/accuracy trade-off with concrete numbers. An operator deploying Aussie for a 5K RPS workload can read this javadoc and set `expectedInsertions` to 1,000,000 without needing to understand bloom filter internals.

### The Local Cache Config Documentation

`LocalCacheConfig` includes similar operational guidance:

```java
// api/src/main/java/aussie/core/cache/LocalCacheConfig.java (lines 84-98)
/**
 * <p>To prevent cache refresh storms in multi-instance deployments,
 * each entry's TTL is varied by +/-(jitterFactor * 100)%. For example,
 * a jitter factor of 0.1 means TTLs vary by +/-10%.
 *
 * <p>This prevents all instances from refreshing their caches at the
 * same time, reducing load spikes on backend storage.
 *
 * <p>Set to 0 to disable jitter (not recommended for production).
 */
@WithDefault("0.1")
double jitterFactor();
```

The documentation explains the why (prevent cache refresh storms), the how (TTL jitter), the default rationale (10% variation), and the risk of changing it (set to 0 = not recommended for production). An operator who sees this property in a troubleshooting scenario understands immediately whether changing it might help or hurt.

### Platform-Level Documentation

The platform README tables (described in Section 5) provide the operator-facing view of the same information. For token revocation, this includes every property in the hierarchy:

```
| Variable                                                     | Default                   | Description                          |
|--------------------------------------------------------------|---------------------------|--------------------------------------|
| AUSSIE_AUTH_REVOCATION_ENABLED                               | true                      | Enable token revocation checks       |
| AUSSIE_AUTH_REVOCATION_CHECK_USER_REVOCATION                 | true                      | Enable user-level revocation         |
| AUSSIE_AUTH_REVOCATION_CHECK_THRESHOLD                       | PT30S                     | Skip check for near-expiry tokens    |
| AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_ENABLED                  | true                      | Enable bloom filter optimization     |
| AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_EXPECTED_INSERTIONS      | 100000                    | Expected number of revoked tokens    |
| AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_FALSE_POSITIVE_PROBABILITY| 0.001                    | Bloom filter false positive rate     |
| AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_REBUILD_INTERVAL         | PT1H                      | Bloom filter rebuild interval        |
| AUSSIE_AUTH_REVOCATION_CACHE_ENABLED                         | true                      | Enable local revocation cache        |
| AUSSIE_AUTH_REVOCATION_CACHE_MAX_SIZE                        | 10000                     | Maximum cache entries                |
| AUSSIE_AUTH_REVOCATION_CACHE_TTL                             | PT5M                      | Cache entry TTL                      |
| AUSSIE_AUTH_REVOCATION_PUBSUB_ENABLED                        | true                      | Enable pub/sub for multi-instance    |
| AUSSIE_AUTH_REVOCATION_PUBSUB_CHANNEL                        | aussie:revocation:events  | Redis pub/sub channel name           |
```

### Timeout Behavior Documentation

The platform README also documents the behavioral semantics of timeouts, not just their values (lines 985-991):

```
Timeout Behavior by Operation:
- HTTP Proxy: Returns 504 Gateway Timeout if upstream doesn't respond
- JWKS Fetch: Falls back to cached keys if available on timeout
- Session Operations: Propagate error (critical operations)
- Cache Reads: Treat timeout as cache miss
- Rate Limiting: Fail-open (allow request) on timeout
- Token Revocation: Fail-closed (deny request) on timeout for security
```

This is the kind of documentation that matters at 3 AM. An operator seeing Redis timeouts needs to know whether their rate limiter is blocking traffic (it is not, it fails open) and whether token revocation is affected (it is, it fails closed). The documentation makes these security-critical behaviors discoverable without reading source code.

### What a Senior Might Do Instead

Document configuration in a wiki page separate from the code. This works initially but drifts as the code evolves. A new property added without updating the wiki is effectively undocumented. Aussie's approach of embedding documentation in the config interface keeps the documentation physically adjacent to the code it describes, which improves the odds of it staying current during code review.

### Trade-offs

Maintaining documentation in two places (interface javadoc and platform README) requires discipline. The interface javadoc is authoritative and detailed. The platform README is the operator-facing summary. When these diverge, operators get confused. The mitigation is to treat both as first-class deliverables during code review, but this relies on team culture rather than automation.

---

## 8. Profile-Specific Overrides

Quarkus profiles allow configuration to vary by deployment environment. Aussie uses three profiles: dev (local development), test (automated tests), and prod (production). The differences between profiles encode operational intent.

### Dev vs. Prod: Storage and Caching

The default `application.properties` configures for development simplicity:

```properties
# api/src/main/resources/application.properties (lines 127-129, 136-137)
# Repository provider selection
# aussie.storage.repository.provider=cassandra   (commented out)
aussie.storage.cache.enabled=false
```

The production profile enables persistent storage and caching:

```properties
# api/src/main/resources/application-prod.properties (lines 9-14)
aussie.storage.repository.provider=cassandra
aussie.storage.cache.enabled=true
aussie.storage.cache.provider=redis
aussie.storage.cache.ttl=PT15M
```

In development, storage is in-memory and caching is disabled. This means developers do not need Cassandra or Redis running locally to work on features unrelated to persistence. In production, Cassandra provides durable storage and Redis provides caching. The profile switch activates the entire storage stack.

### Dev vs. Prod: Connection Pools

The default configuration sets conservative connection pools:

```properties
# api/src/main/resources/application.properties (lines 103-104, 118-119)
aussie.resiliency.cassandra.pool-local-size=30
aussie.resiliency.redis.pool-size=30
aussie.resiliency.redis.pool-waiting=100
aussie.resiliency.http.max-connections-per-host=50
aussie.resiliency.http.max-connections=200
```

The production profile scales them up:

```properties
# api/src/main/resources/application.properties (lines 61-66, using %prod prefix)
%prod.aussie.resiliency.cassandra.pool-local-size=50
%prod.aussie.resiliency.redis.pool-size=50
%prod.aussie.resiliency.redis.pool-waiting=200
%prod.aussie.resiliency.http.max-connections-per-host=100
%prod.aussie.resiliency.http.max-connections=500
%prod.aussie.resiliency.jwks.max-connections=20
```

The production connection pool for HTTP goes from 200 total connections to 500, and per-host from 50 to 100. Redis pool waiting goes from 100 to 200. These are not arbitrary -- the comments (lines 60-61) explain the intent: "Scale these based on: (expected_concurrent_requests x avg_requests_per_connection)."

### Dev vs. Prod: Session Storage

Session storage switches between profiles:

```properties
# api/src/main/resources/application.properties (lines 323, 334)
aussie.session.storage.provider=redis
%dev.aussie.session.storage.provider=memory
%dev.aussie.session.cookie.secure=false
```

In development, sessions are stored in memory and cookies do not require HTTPS. In production, sessions use Redis for cross-instance persistence and cookies require HTTPS. The `secure=false` override for dev is necessary because local development typically runs on `http://localhost`, and secure cookies would be silently dropped by the browser.

### Dev vs. Prod: Event Loop Detection

Vert.x event loop blocking detection is tuned differently per profile:

```properties
# api/src/main/resources/application.properties (lines 52-57)
quarkus.vertx.warning-exception-time=2s
quarkus.vertx.max-event-loop-execute-time=5s

%dev.quarkus.vertx.warning-exception-time=1s
%dev.quarkus.vertx.max-event-loop-execute-time=2s
```

Development uses tighter thresholds (1s warning, 2s max) to catch blocking operations early. Production uses more lenient thresholds (2s warning, 5s max) to avoid false alarms from transient GC pauses or warm-up behavior. The comments explain: "Dev profile: more aggressive blocking detection for development."

### Dev vs. Prod: Feature Flags

Several features have dev-specific overrides:

```properties
# api/src/main/resources/application.properties (lines 356, 397-401)
%dev.aussie.auth.pkce.storage.provider=memory
%dev.aussie.auth.oidc.token-exchange.enabled=true
%dev.aussie.auth.oidc.token-exchange.token-endpoint=http://localhost:3000/api/auth/oidc/token
%dev.aussie.auth.oidc.token-exchange.client-id=aussie-gateway
%dev.aussie.auth.oidc.token-exchange.client-secret=demo-secret
```

PKCE storage uses memory instead of Redis. OIDC token exchange is enabled with a demo endpoint. The dev profile assembles a self-contained development environment that does not require external infrastructure.

### Test Profile Overrides

The test profile disables features that interfere with automated testing:

```properties
# api/src/main/resources/application.properties (line 562)
%test.aussie.auth.rate-limit.enabled=false
```

Authentication rate limiting is disabled in tests because test suites that exercise authentication would trip the brute force protection after five failures, causing cascading test failures unrelated to the code under test.

### Production Secrets via Environment Variables

The production profile references secrets through environment variables with empty defaults:

```properties
# api/src/main/resources/application-prod.properties (lines 30-31, 38)
aussie.storage.cassandra.username=${CASSANDRA_USERNAME:}
aussie.storage.cassandra.password=${CASSANDRA_PASSWORD:}
quarkus.redis.password=${REDIS_PASSWORD:}
```

The `${VAR:}` syntax means: use the environment variable if set, otherwise use an empty string. This allows Aussie to start without credentials (for environments where Cassandra and Redis do not require authentication) while making it trivial to inject credentials when required.

### What a Senior Might Do Instead

Use a single `application.properties` with no profiles, overriding everything through environment variables. This is simpler and avoids the cognitive overhead of tracking which profile sets which value. But it pushes the configuration burden entirely to operators, who must set dozens of environment variables even for features with obvious dev/prod differences (like using in-memory storage for development).

Another approach is separate configuration files per environment (application-dev.properties, application-staging.properties, application-prod.properties), which can lead to configuration drift between environments. Quarkus profiles with inline `%profile.` prefixes keep dev and prod configuration visible in the same file, making differences between environments immediately apparent.

### Trade-offs

The inline profile prefix syntax (`%prod.aussie.resiliency.redis.pool-size=50`) intermixes dev and prod configuration in one file. For a file with hundreds of properties, this can be hard to scan. Aussie mitigates this by grouping prod overrides near their default counterparts (lines 59-66 are production connection pool overrides directly after the threading model section). The separate `application-prod.properties` file handles overrides that are large enough to warrant their own section (storage provider selection, Cassandra auth, Redis auth).

The decision of what goes in the inline `%prod.` prefix versus the separate `application-prod.properties` is pragmatic: connection pool sizing stays inline because it benefits from being visible alongside the defaults. Storage and auth configuration gets its own file because it represents a qualitative change in behavior (in-memory to Cassandra), not a quantitative change (30 to 50 connections).

---

## Summary

Configuration in Aussie is not an afterthought. It is a layered safety system with specific design principles:

1. **Type-safe interfaces with defaults** prevent misconfiguration from compiling. `@ConfigMapping` interfaces with `@WithDefault` mean every property has a declared type and a safe fallback.

2. **Hierarchical resolution with platform ceilings** allows service teams autonomy within platform-enforced limits. The `RateLimitResolver` implements this as endpoint overrides service overrides platform defaults, all capped at `platformMaxRequestsPerWindow`.

3. **`Instance<T>` for optional features** makes feature presence explicit in the type system. `isResolvable()` replaces null checks with a CDI-native existence query.

4. **Startup guards** convert configuration errors from runtime surprises to deployment failures. The `NoopAuthGuard` ensures dangerous configurations never serve production traffic.

5. **Exhaustive documentation** appears both in interface javadoc (for developers) and platform README tables (for operators), with operational guidance beyond "what this does" into "how to choose a value."

6. **Explicit secrets management** separates what can be committed from what must be injected, with graceful degradation (plaintext fallback with warning) where appropriate.

7. **Profile-specific overrides** encode the operational differences between development and production, from connection pool sizing to storage backends to event loop detection thresholds.

Each of these patterns has alternatives that are simpler in the short term. Flat configuration is easier to implement than hierarchical resolution. Direct injection is less verbose than `Instance<T>`. No startup guards means faster iteration. The Aussie codebase accepts the complexity because the alternative -- discovering configuration errors in production -- is a category of incident that should not exist in well-designed infrastructure.
