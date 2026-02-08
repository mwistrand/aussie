# Chapter 1: Architecture -- Hexagonal Design and Dependency Discipline

## The Core Bet

The Aussie API Gateway makes an architectural bet that most gateway projects never make: the entire domain model and business logic compile, run, and test without Quarkus, Jakarta, Vert.x, Cassandra, or Redis on the classpath. This is hexagonal architecture applied with discipline, not as a diagram on a wiki page but as a set of enforced package boundaries and compilation rules.

This chapter explains what was done, why it matters, what alternatives exist, and where the trade-offs bite.

---

## 1. Package Layout: The Hexagon in Practice

The codebase is organized into three layers with strict, unidirectional dependency flow:

```
aussie/
  core/
    model/       # Domain objects: records, sealed interfaces, value types
      auth/      # Authentication/authorization models
      common/    # Cross-cutting config interfaces, validation
      gateway/   # Gateway request/response models
      ratelimit/ # Rate limiting domain models
      routing/   # Route matching, endpoint configuration
      sampling/  # OTel sampling configuration
      service/   # ServiceRegistration and related types
      session/   # Session models
      websocket/ # WebSocket upgrade models
    port/
      in/        # Use cases: GatewayUseCase, PassThroughUseCase, etc.
      out/       # Repository/infrastructure interfaces: ProxyClient, Metrics, etc.
    service/     # Domain services implementing use cases
      auth/      # Token validation, key rotation, RBAC
      common/    # Client IP extraction, request validation
      gateway/   # GatewayService, PassThroughService
      ratelimit/ # Rate limit resolution
      routing/   # ServiceRegistry, endpoint matching
      session/   # Session management

  adapter/
    in/          # Driving adapters: REST resources, auth mechanisms, DTOs
      auth/      # JWT/API key/session authentication mechanisms
      bootstrap/ # Startup initialization
      dto/       # Request/response DTOs for the REST API
      health/    # Health check endpoints
      http/      # CORS, security headers, OIDC/JWKS endpoints
      problem/   # RFC 9457 problem details
      rest/      # JAX-RS resource classes
      validation/# Input validation
      websocket/ # WebSocket gateway adapter
    out/         # Driven adapters: HTTP clients, storage, telemetry
      auth/      # Signing key repos, token translators, OIDC validators
      http/      # ProxyHttpClient, config mapping, forwarded headers
      ratelimit/ # In-memory/Redis rate limiter implementations
      storage/   # Cassandra/Redis/in-memory repository implementations
      telemetry/ # Metrics, sampling, security event dispatching
      threading/ # Vert.x context utilities

  system/
    filter/      # Cross-cutting JAX-RS filters (rate limit, auth, access control)
```

The key structural insight is the separation of `core/model` from `core/port` from `core/service`. Models are pure data -- records, sealed interfaces, value objects. Ports are contracts -- interfaces that declare what the domain needs from the outside world. Services implement business logic by composing models and ports. None of them know what framework hosts them.

### Why This Matters

A gateway is infrastructure that outlives any single framework choice. Quarkus is excellent today; something else may be excellent in three years. By keeping the domain layer framework-free, the codebase can migrate frameworks without rewriting business logic. More immediately, it means domain services can be unit tested with plain JUnit and Mockito, no `@QuarkusTest` annotation needed, no container startup, no 5-second test feedback loops.

### What a Senior Engineer Might Have Done Instead

The most common alternative is a "layered architecture" where `@ApplicationScoped` service classes directly import Jakarta annotations, Quarkus config objects, and Vert.x types. This works fine for small services. The problem is that it creates an implicit dependency graph where changing frameworks means touching every file. In a gateway that handles authentication, rate limiting, CORS, WebSocket proxying, token translation, and RBAC, "every file" is a lot of files.

Another common approach is to skip the port layer entirely and have services depend directly on repository implementations. This trades testability for fewer files. In a project with 20+ repository interfaces and multiple storage backends (Cassandra, Redis, in-memory), that trade-off is clearly wrong.

### Trade-offs

The hexagonal structure introduces indirection. `GatewayService` does not call `ProxyHttpClient` -- it calls `ProxyClient`, an interface in `core/port/out`. Someone reading the code must follow the interface to its implementation. In an IDE this is a single keystroke; in a code review it requires navigating to the adapter layer.

The package count is also higher. There are nine subdirectories under `core/model` alone. For a team unfamiliar with hexagonal architecture, this can feel over-structured. The counterargument is that the structure is self-documenting: if you want to understand rate limiting, look in `core/model/ratelimit` for the domain model, `core/service/ratelimit` for the logic, and `adapter/out/ratelimit` for the implementations.

---

## 2. ArchUnit Tests: Architecture Rules That Break the Build

Architecture diagrams rot. Wiki pages describing "which layer can depend on which" go stale the first time someone adds an expedient import. Aussie solves this by encoding architectural rules as tests that fail the build.

**File:** `/Users/mwistrand/projects/java/aussie/api/src/test/java/aussie/HexagonalArchitectureTest.java`

### The Core Isolation Rules

```java
@Nested
@DisplayName("Core Layer Rules")
class CoreLayerRules {

    @Test
    @DisplayName("Core should not depend on adapter")
    void coreShouldNotDependOnAdapter() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("aussie.core..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("aussie.adapter..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Core should not depend on system")
    void coreShouldNotDependOnSystem() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("aussie.core..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("aussie.system..");

        rule.check(importedClasses);
    }
}
```

These two rules (lines 33-55) are the load-bearing walls of the architecture. If any class in `aussie.core` imports anything from `aussie.adapter` or `aussie.system`, the test fails and the build breaks. This makes the hexagonal boundary a compilation constraint, not a convention.

### The Port Interface Rule

```java
@Nested
@DisplayName("Port Interface Rules")
class PortInterfaceRules {

    @Test
    @DisplayName("Outbound ports should only contain interfaces")
    void outboundPortsShouldBeInterfaces() {
        ArchRule rule = ArchRuleDefinition.classes()
                .that()
                .resideInAPackage("aussie.core.port.out..")
                .should()
                .beInterfaces();

        rule.check(importedClasses);
    }
}
```

This rule (lines 150-160) enforces that outbound ports are always interfaces. You cannot sneak a concrete class into `core/port/out`. This guarantees that every driven adapter is substitutable -- critical for testing and for supporting multiple storage backends.

### The Model Isolation Rules

```java
@Nested
@DisplayName("Model Rules")
class ModelRules {

    @Test
    @DisplayName("Models should not depend on services")
    void modelsShouldNotDependOnServices() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("aussie.core.model..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("aussie.core.service..");

        rule.check(importedClasses);
    }

    @Test
    @DisplayName("Models should not depend on ports")
    void modelsShouldNotDependOnPorts() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("aussie.core.model..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("aussie.core.port..");

        rule.check(importedClasses);
    }
}
```

Lines 166-191 enforce that models are the innermost ring. They cannot reach outward to services or ports. This prevents the common anti-pattern where a domain object acquires a reference to a repository to lazy-load related data. Models in Aussie are inert data structures -- they carry information, they validate their own invariants, and nothing else.

### The Adapter Isolation Rule

```java
@Test
@DisplayName("Adapter should not depend on system")
void adapterShouldNotDependOnSystem() {
    ArchRule rule = noClasses()
            .that()
            .resideInAPackage("aussie.adapter..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("aussie.system..");

    rule.check(importedClasses);
}
```

Lines 64-73 enforce that adapters and system filters live in separate dependency trees. The `system/filter` layer is permitted to depend on both core and adapter (it is the outermost ring), but adapters cannot depend on it. This prevents filters from becoming a junk drawer that adapters reach into.

### Why This Matters

ArchUnit tests transform architecture from an honor system into a verified property. New team members cannot accidentally violate the dependency rules because CI will reject their PR. This is especially valuable on a gateway project where many contributors may add features (new auth mechanisms, new storage backends, new rate limiters) without deep familiarity with the architectural intent.

### Trade-offs

ArchUnit scans compiled bytecode, which means the test catches violations only after compilation. In practice this is fast enough (the scan imports the `aussie` package tree once in `@BeforeAll` and all rules execute against the same snapshot). The larger trade-off is that ArchUnit rules are written in Java, not in a declarative config file. If the rules need updating, a developer must understand the ArchUnit DSL. The tests are readable enough that this has not been a practical issue.

---

## 3. ConfigMapping Interfaces: The Configuration Boundary

Quarkus provides `@ConfigMapping` as a type-safe alternative to `@ConfigProperty`. Aussie uses this mechanism at two levels. The adapter layer defines `GatewayConfig` with full Quarkus-specific annotations and nested structure. The core layer also uses `@ConfigMapping` directly on focused, single-concern config interfaces (e.g., `ResiliencyConfig`, `RateLimitingConfig`, `LocalCacheConfig`). This is a pragmatic exception to strict hexagonal purity: SmallRye Config's `@ConfigMapping` is the one permitted framework dependency in `core/config` and `core/cache`, because extracting it to the adapter layer would require dozens of boilerplate producer methods for no meaningful gain.

### The Adapter Side: GatewayConfig

**File:** `/Users/mwistrand/projects/java/aussie/api/src/main/java/aussie/adapter/out/http/GatewayConfig.java`

```java
@ConfigMapping(prefix = "aussie.gateway")
public interface GatewayConfig {

    ForwardingConfig forwarding();

    LimitsConfig limits();

    AccessControlConfig accessControl();

    SecurityConfig security();

    TrustedProxyConfig trustedProxy();
}
```

`GatewayConfig` is the Quarkus entry point. The `@ConfigMapping(prefix = "aussie.gateway")` annotation tells Quarkus to bind properties like `aussie.gateway.limits.max-body-size` to the nested `LimitsConfig.maxBodySize()` method. Notice that `GatewayConfig` itself lives in `aussie.adapter.out.http` -- the adapter layer. But several of the interfaces it returns (`LimitsConfig`, `AccessControlConfig`, `TrustedProxyConfig`) are defined in `aussie.core.model.common` or `aussie.core.model.auth`.

### The Core Side: Config Interfaces as Domain Contracts

**File:** `/Users/mwistrand/projects/java/aussie/api/src/main/java/aussie/core/model/common/LimitsConfig.java`

```java
package aussie.core.model.common;

import io.smallrye.config.WithDefault;

public interface LimitsConfig {

    @WithDefault("10485760")
    long maxBodySize();

    @WithDefault("8192")
    int maxHeaderSize();

    @WithDefault("32768")
    int maxTotalHeadersSize();
}
```

`LimitsConfig` is a plain interface. Core services like `RequestSizeValidator` depend on `LimitsConfig`, not on `GatewayConfig` or any Quarkus type. The interface does use `@WithDefault` from SmallRye Config, which is a minor framework leak -- SmallRye is a MicroProfile Config implementation, and the annotation is used by Quarkus to provide defaults. This is a pragmatic compromise: the alternative (duplicating default values in a CDI producer) would be more verbose without adding real portability.

### The Bridge: ConfigProducer

**File:** `/Users/mwistrand/projects/java/aussie/api/src/main/java/aussie/adapter/out/http/ConfigProducer.java`

```java
@ApplicationScoped
public class ConfigProducer {

    private final GatewayConfig gatewayConfig;

    @Inject
    public ConfigProducer(GatewayConfig gatewayConfig) {
        this.gatewayConfig = gatewayConfig;
    }

    @Produces
    @ApplicationScoped
    public LimitsConfig limitsConfig() {
        return gatewayConfig.limits();
    }

    @Produces
    @ApplicationScoped
    public AccessControlConfig accessControlConfig() {
        return gatewayConfig.accessControl();
    }

    @Produces
    @ApplicationScoped
    public GatewaySecurityConfig gatewaySecurityConfig() {
        return gatewayConfig.security();
    }

    @Produces
    @ApplicationScoped
    public TrustedProxyConfig trustedProxyConfig() {
        return gatewayConfig.trustedProxy();
    }
}
```

`ConfigProducer` is the CDI bridge. It takes the adapter-layer `GatewayConfig` (which Quarkus populates via `@ConfigMapping`) and produces individual core-layer config interfaces as CDI beans. When `RequestSizeValidator` needs `LimitsConfig`, CDI injects it directly. The validator has no idea that the value came from `GatewayConfig` or Quarkus config files -- it just receives an interface it can call.

This pattern is worth examining line by line. The `gatewayConfig.security()` method returns a `SecurityConfig` (adapter layer), but the producer method's return type is `GatewaySecurityConfig` (core layer). This works because `SecurityConfig` extends `GatewaySecurityConfig`:

**File:** `/Users/mwistrand/projects/java/aussie/api/src/main/java/aussie/adapter/out/http/SecurityConfig.java`

```java
package aussie.adapter.out.http;

import io.smallrye.config.WithDefault;

import aussie.core.model.auth.GatewaySecurityConfig;

public interface SecurityConfig extends GatewaySecurityConfig {

    @WithDefault("false")
    @Override
    boolean publicDefaultVisibilityEnabled();
}
```

The core layer defines `GatewaySecurityConfig` as a plain interface with a single method. The adapter layer's `SecurityConfig` extends it and adds the `@WithDefault` annotation that Quarkus needs. The `ConfigProducer` returns it typed as the core interface. This is interface segregation applied to configuration.

### Why This Matters

Configuration is one of the most common places where framework coupling creeps into domain logic. Once a core service imports `@ConfigMapping`, it cannot be tested without Quarkus's config machinery. The `ConfigProducer` bridge means core services receive simple interfaces that can be trivially mocked or stubbed in tests:

```java
// In a unit test -- no Quarkus container needed
var limits = new LimitsConfig() {
    public long maxBodySize() { return 1024; }
    public int maxHeaderSize() { return 256; }
    public int maxTotalHeadersSize() { return 512; }
};
var validator = new RequestSizeValidator(limits);
```

### What a Senior Engineer Might Have Done Instead

The most common approach is to inject `@ConfigMapping` interfaces directly into services and annotate those services with `@ApplicationScoped`. This eliminates `ConfigProducer` entirely. The cost is that every service that reads configuration now requires a running Quarkus container (or at minimum the SmallRye Config test infrastructure) to test.

Another approach is to use `@ConfigProperty` on individual fields. This is simpler for one or two properties but becomes unwieldy when a service needs a cohesive group of related settings (max body size, max header size, max total headers size).

### Trade-offs

The `ConfigProducer` is boilerplate. Every new config group requires a new producer method. This is a real cost -- perhaps a dozen lines of code per config group. The benefit is that the core layer remains a clean compilation unit. For a project with the complexity of Aussie (authentication, authorization, rate limiting, WebSocket proxying, token translation, CORS, security headers, traffic attribution, observability), the boilerplate cost is easily justified.

The `@WithDefault` annotation on core-layer interfaces (`LimitsConfig`, `TrustedProxyConfig`) is a small framework leak. If portability to a non-MicroProfile framework were a goal, these annotations would need to be removed and defaults applied in the producer. In practice, MicroProfile Config is stable enough that this is not a concern.

---

## 4. Sealed Interfaces: Bounded Type Hierarchies for Domain Results

Aussie makes extensive use of Java's `sealed` interfaces to model operation outcomes. The codebase contains over a dozen sealed interfaces, concentrated in `core/model`. These are not a stylistic preference -- they serve a specific architectural purpose: making it impossible to handle a result without considering all possible outcomes.

### GatewayResult: The Central Response Type

**File:** `/Users/mwistrand/projects/java/aussie/api/src/main/java/aussie/core/model/gateway/GatewayResult.java`

```java
public sealed interface GatewayResult {

    record Success(int statusCode, Map<String, List<String>> headers, byte[] body)
            implements GatewayResult {
        public Success {
            if (headers == null) { headers = Map.of(); }
            if (body == null) { body = new byte[0]; }
        }

        public static Success from(ProxyResponse response) {
            return new Success(response.statusCode(), response.headers(), response.body());
        }
    }

    record RouteNotFound(String path) implements GatewayResult {}
    record ServiceNotFound(String serviceId) implements GatewayResult {}
    record ReservedPath(String path) implements GatewayResult {}
    record Error(String message) implements GatewayResult {}
    record Unauthorized(String reason) implements GatewayResult {}
    record Forbidden(String reason) implements GatewayResult {}
    record BadRequest(String reason) implements GatewayResult {}
}
```

`GatewayResult` has eight variants. Because the interface is sealed, the compiler knows all possible subtypes. When `GatewayService` returns a `GatewayResult`, the calling code (typically a REST resource or filter) must handle every variant or the `switch` expression will produce a compile-time warning (or error, with the right compiler flags).

This pattern is used consistently for `RouteAuthResult` (five variants: `Authenticated`, `NotRequired`, `Unauthorized`, `Forbidden`, `BadRequest`), `AuthenticationResult` (three variants: `Success`, `Failure`, `Skip`), `TokenValidationResult` (three variants: `Valid`, `Invalid`, `NoToken`), `RegistrationResult` (two variants: `Success`, `Failure`), `ValidationResult` (two variants: `Valid`, `Invalid`), and others.

### Pattern Matching Over Sealed Types

The sealed interface + switch expression combination is used in `GatewayService.handleAuthResult`:

**File:** `/Users/mwistrand/projects/java/aussie/api/src/main/java/aussie/core/service/gateway/GatewayService.java`, lines 128-137

```java
private Uni<GatewayResult> handleAuthResult(
        RouteAuthResult authResult, GatewayRequest request, RouteMatch routeMatch) {
    return switch (authResult) {
        case RouteAuthResult.Authenticated auth -> forwardWithToken(request, routeMatch, auth.token());
        case RouteAuthResult.NotRequired notRequired -> forwardWithoutToken(request, routeMatch);
        case RouteAuthResult.Unauthorized unauthorized -> Uni.createFrom()
                .item(new GatewayResult.Unauthorized(unauthorized.reason()));
        case RouteAuthResult.Forbidden forbidden -> Uni.createFrom()
                .item(new GatewayResult.Forbidden(forbidden.reason()));
        case RouteAuthResult.BadRequest badRequest -> Uni.createFrom()
                .item(new GatewayResult.BadRequest(badRequest.reason()));
    };
}
```

This switch is exhaustive. If someone adds a sixth variant to `RouteAuthResult`, this method will fail to compile until it handles the new case. This is the primary value of sealed interfaces: they turn "did I handle all the cases?" from a runtime question into a compile-time guarantee.

### RouteLookupResult: Sealed Interfaces with Default Methods

**File:** `/Users/mwistrand/projects/java/aussie/api/src/main/java/aussie/core/model/routing/RouteLookupResult.java`

```java
public sealed interface RouteLookupResult permits RouteMatch, ServiceOnlyMatch {

    ServiceRegistration service();

    Optional<EndpointConfig> endpoint();

    default EndpointVisibility visibility() {
        return endpoint().map(EndpointConfig::visibility)
                .orElseGet(() -> service().defaultVisibility());
    }

    default boolean authRequired() {
        return endpoint().map(EndpointConfig::authRequired)
                .orElseGet(() -> service().defaultAuthRequired());
    }

    default Optional<ServiceRateLimitConfig> rateLimitConfig() {
        return endpoint()
                .flatMap(EndpointConfig::rateLimitConfig)
                .map(erc -> new ServiceRateLimitConfig(
                        erc.requestsPerWindow(), erc.windowSeconds(), erc.burstCapacity()))
                .or(() -> service().rateLimitConfig());
    }
}
```

`RouteLookupResult` demonstrates a more sophisticated use of sealed interfaces. It has exactly two permitted subtypes (`RouteMatch` and `ServiceOnlyMatch`), and it provides default method implementations that resolve effective configuration by checking the endpoint first and falling back to service defaults. This pushes configuration resolution logic into the type itself rather than scattering it across calling code.

### RevocationEvent: Domain Events as Sealed Types

**File:** `/Users/mwistrand/projects/java/aussie/api/src/main/java/aussie/core/model/auth/RevocationEvent.java`

```java
public sealed interface RevocationEvent {

    record JtiRevoked(String jti, Instant expiresAt) implements RevocationEvent {}

    record UserRevoked(String userId, Instant issuedBefore, Instant expiresAt)
            implements RevocationEvent {}
}
```

This is the cleanest example in the codebase. A revocation is either for a specific token (by JTI) or for all of a user's tokens issued before a timestamp. There is no third option. The sealed interface makes this explicit in the type system.

### Why This Matters

The alternative to sealed interfaces is typically an enum + data class combination, or a base class with `instanceof` checks, or (worst) a single class with nullable fields where different fields are populated for different "types." Sealed interfaces give you:

1. **Exhaustiveness checking** in switch expressions -- the compiler catches missing cases
2. **Per-variant data** -- each record carries exactly the fields relevant to that outcome
3. **No null fields** -- `GatewayResult.RouteNotFound` has a `path`, not a nullable `statusCode`
4. **Compact constructors** for validation on each variant independently

### What a Senior Engineer Might Have Done Instead

The pre-Java 17 approach would be an abstract base class with subtypes, or an enum with a generic data payload. Both lose exhaustiveness checking. Some teams use a `Result<S, F>` generic type (similar to Rust's `Result`). This works well for binary success/failure but becomes awkward when there are eight possible outcomes, as with `GatewayResult`.

Another approach is to throw exceptions for error cases. This is simpler in the happy path but makes error handling invisible -- you must read the Javadoc (if it exists) to know what exceptions a method might throw. Sealed interfaces make every possible outcome visible in the return type.

### Trade-offs

Sealed interfaces produce many small record classes. `GatewayResult` alone defines eight inner records. This increases the surface area of the type system. For simple two-state results (`ValidationResult`), the overhead of a sealed interface over a boolean return is real. The codebase accepts this cost for the sake of consistency: every operation result uses the same pattern, making the codebase predictable.

The `RateLimitState` sealed interface (`permits BucketState`) currently has only one permitted implementation. This is forward-looking design -- it anticipates sliding window or fixed window implementations. If those never materialize, the sealed interface adds indirection without benefit.

---

## 5. Immutable Records: ServiceRegistration as a Rich Domain Model

`ServiceRegistration` is the central domain object in Aussie. It defines how the gateway routes requests to a backend service. It is implemented as a Java record -- immutable by default, with a compact constructor that enforces invariants.

**File:** `/Users/mwistrand/projects/java/aussie/api/src/main/java/aussie/core/model/service/ServiceRegistration.java`

### The Record Declaration (lines 53-67)

```java
public record ServiceRegistration(
        String serviceId,
        String displayName,
        URI baseUrl,
        String routePrefix,
        EndpointVisibility defaultVisibility,
        boolean defaultAuthRequired,
        List<VisibilityRule> visibilityRules,
        List<EndpointConfig> endpoints,
        Optional<ServiceAccessConfig> accessConfig,
        Optional<CorsConfig> corsConfig,
        Optional<ServicePermissionPolicy> permissionPolicy,
        Optional<ServiceRateLimitConfig> rateLimitConfig,
        Optional<ServiceSamplingConfig> samplingConfig,
        long version) {
```

This is a 14-component record. Every field is part of the public API. The record is immutable -- once created, a `ServiceRegistration` cannot be modified.

### The Compact Constructor (lines 68-108)

```java
public ServiceRegistration {
    if (serviceId == null || serviceId.isBlank()) {
        throw new IllegalArgumentException("Service ID cannot be null or blank");
    }
    if (displayName == null || displayName.isBlank()) {
        throw new IllegalArgumentException("Display name cannot be null or blank");
    }
    if (baseUrl == null) {
        throw new IllegalArgumentException("Base URL cannot be null");
    }
    if (routePrefix == null || routePrefix.isBlank()) {
        routePrefix = "/" + serviceId;
    }
    if (defaultVisibility == null) {
        defaultVisibility = EndpointVisibility.PRIVATE;
    }
    if (visibilityRules == null) {
        visibilityRules = List.of();
    }
    if (endpoints == null) {
        endpoints = List.of();
    }
    if (accessConfig == null) {
        accessConfig = Optional.empty();
    }
    if (corsConfig == null) {
        corsConfig = Optional.empty();
    }
    if (permissionPolicy == null) {
        permissionPolicy = Optional.empty();
    }
    if (rateLimitConfig == null) {
        rateLimitConfig = Optional.empty();
    }
    if (samplingConfig == null) {
        samplingConfig = Optional.empty();
    }
    if (version < 0) {
        version = 1;
    }
}
```

The compact constructor does two things: it validates required fields (throwing `IllegalArgumentException` for null/blank `serviceId`, `displayName`, or `baseUrl`) and it normalizes optional fields (replacing nulls with safe defaults). This is a defensive pattern -- callers can pass null for optional fields and the constructor will do the right thing, while required fields are enforced.

The distinction between throwing and defaulting is deliberate: `serviceId` is an identity field that must be explicitly provided, so null is an error. `defaultVisibility` has a sensible default (`PRIVATE`), so null is silently corrected. This prevents a class of bugs where a service is accidentally registered as publicly visible because a developer forgot to set the visibility field.

### Domain Behavior on Records (lines 202-224)

`ServiceRegistration` is not a passive data carrier. It contains real domain logic:

```java
public Optional<RouteMatch> findRoute(String path, String method) {
    final var normalizedPath = normalizePath(path);
    final var upperMethod = method.toUpperCase();

    for (final var endpoint : endpoints) {
        if (!endpoint.methods().contains(upperMethod) && !endpoint.methods().contains("*")) {
            continue;
        }

        final var pattern = compilePathPattern(endpoint.path());
        final var matcher = pattern.matcher(normalizedPath);
        if (matcher.matches()) {
            final var pathVariables = extractPathVariables(endpoint.path(), matcher);
            final var targetPath = endpoint.pathRewrite()
                    .map(rewrite -> applyPathRewrite(rewrite, pathVariables, normalizedPath))
                    .orElse(normalizedPath);

            return Optional.of(new RouteMatch(this, endpoint, targetPath, pathVariables));
        }
    }

    return Optional.empty();
}
```

Route matching -- including path normalization, pattern compilation, path variable extraction, and path rewriting -- is behavior that belongs to the service registration itself. This is a DDD-informed design choice: a `ServiceRegistration` knows how to match its own routes because it owns the endpoint definitions. Moving this logic to an external service would require passing the full endpoint list around and would scatter related logic across multiple classes.

### Wither Methods for Immutable Updates (lines 113-192)

Since records are immutable, mutation is expressed through "wither" methods that return new instances:

```java
public ServiceRegistration withIncrementedVersion() {
    return new ServiceRegistration(
            serviceId, displayName, baseUrl, routePrefix,
            defaultVisibility, defaultAuthRequired,
            visibilityRules, endpoints,
            accessConfig, corsConfig, permissionPolicy,
            rateLimitConfig, samplingConfig,
            version + 1);
}

public ServiceRegistration withPermissionPolicy(ServicePermissionPolicy policy) {
    return new ServiceRegistration(
            serviceId, displayName, baseUrl, routePrefix,
            defaultVisibility, defaultAuthRequired,
            visibilityRules, endpoints,
            accessConfig, corsConfig,
            Optional.ofNullable(policy),
            rateLimitConfig, samplingConfig,
            version);
}
```

Each wither method copies all 14 fields, changing only the relevant one. This is verbose -- the 14-argument constructor call is repeated in every wither method. This is the primary ergonomic cost of using records for rich domain objects.

### Why This Matters

Immutability matters in a concurrent system. A `ServiceRegistration` can be safely shared across Vert.x event loop threads, cached in a `ConcurrentHashMap`, published via a reactive stream, and compared with a previous version -- all without synchronization. The `version` field enables optimistic locking for concurrent updates.

Records also provide `equals()`, `hashCode()`, and `toString()` for free, based on all components. For a 14-field object, hand-writing these methods is error-prone. The record ensures that two `ServiceRegistration` instances with identical fields are equal.

### What a Senior Engineer Might Have Done Instead

The most common alternative is a mutable POJO with getters and setters. This eliminates the wither methods but introduces thread-safety concerns and makes it possible to create partially-initialized objects.

Another alternative is to use Lombok's `@Value` and `@Builder` annotations. This provides immutability and a builder with less boilerplate. The trade-off is a compile-time annotation processor dependency and generated code that is invisible in the source.

A third option is to keep the record but reduce its field count by grouping related fields into sub-records (e.g., a `ServiceDefaults` record for `defaultVisibility` + `defaultAuthRequired`). This would reduce the wither method verbosity but add another layer of nesting.

### Trade-offs

The 14-component record is at the upper edge of what is comfortable for a record. The canonical constructor has 14 parameters, which makes direct construction error-prone (it is easy to swap two `Optional` parameters). The Builder pattern (covered next) mitigates this. The wither methods are repetitive, but they are mechanically simple and hard to get wrong.

---

## 6. Builder Pattern: Constructing Complex Domain Objects

With 14 components, directly constructing a `ServiceRegistration` is impractical for callers. The record includes a nested `Builder` class.

**File:** `/Users/mwistrand/projects/java/aussie/api/src/main/java/aussie/core/model/service/ServiceRegistration.java`, lines 274-385

### Builder Structure

```java
public static Builder builder(String serviceId) {
    return new Builder(serviceId);
}

public static class Builder {
    private final String serviceId;
    private String displayName;
    private URI baseUrl;
    private String routePrefix;
    private EndpointVisibility defaultVisibility;
    private boolean defaultAuthRequired = true;
    private List<VisibilityRule> visibilityRules = List.of();
    private List<EndpointConfig> endpoints = List.of();
    private ServiceAccessConfig accessConfig;
    private CorsConfig corsConfig;
    private ServicePermissionPolicy permissionPolicy;
    private ServiceRateLimitConfig rateLimitConfig;
    private ServiceSamplingConfig samplingConfig;
    private long version = 1;
```

The builder takes `serviceId` as a constructor parameter (line 294), making it impossible to forget the required identity field. The other required fields (`displayName`, `baseUrl`) are left as nulls that the record's compact constructor will catch. This is a design choice: `serviceId` is enforced by the builder, while `baseUrl` is enforced by the record. Ideally both would be enforced at the builder level, but the current approach still fails fast with a clear error message.

### Type Overloading for Ergonomics

```java
public Builder baseUrl(URI baseUrl) {
    this.baseUrl = baseUrl;
    return this;
}

public Builder baseUrl(String baseUrl) {
    this.baseUrl = URI.create(baseUrl);
    return this;
}
```

The builder provides two overloads for `baseUrl` -- one accepting a `URI`, one accepting a `String`. This is a small ergonomic win: test code and configuration code typically have URLs as strings, while domain code works with `URI` objects.

### The Build Method

```java
public ServiceRegistration build() {
    return new ServiceRegistration(
            serviceId,
            displayName != null ? displayName : serviceId,
            baseUrl,
            routePrefix,
            defaultVisibility,
            defaultAuthRequired,
            visibilityRules,
            endpoints,
            Optional.ofNullable(accessConfig),
            Optional.ofNullable(corsConfig),
            Optional.ofNullable(permissionPolicy),
            Optional.ofNullable(rateLimitConfig),
            Optional.ofNullable(samplingConfig),
            version);
}
```

The `build()` method applies one additional default: if `displayName` was not set, it falls back to `serviceId`. It also wraps nullable fields in `Optional.ofNullable()`, so callers never need to construct `Optional` instances themselves. The builder accepts nulls; the record stores `Optional`.

### Why This Matters

The builder makes test code and deserialization code readable:

```java
var registration = ServiceRegistration.builder("user-service")
        .displayName("User Service")
        .baseUrl("http://users.internal:8080")
        .defaultVisibility(EndpointVisibility.PRIVATE)
        .defaultAuthRequired(true)
        .endpoints(List.of(
            EndpointConfig.publicEndpoint("/health", Set.of("GET")),
            EndpointConfig.privateEndpoint("/users/{id}", Set.of("GET", "PUT"))
        ))
        .build();
```

Compare this with the 14-argument constructor call. The builder makes it clear which fields are being set and which are taking defaults.

### What a Senior Engineer Might Have Done Instead

Java records do not natively support builders, which means the builder must be hand-written. Lombok's `@Builder` annotation would generate it, but Aussie does not use Lombok. For a single record type, hand-writing the builder is reasonable. If the codebase had many large records that all needed builders, Lombok or a code generation approach would reduce maintenance overhead.

Another alternative is to use a "step builder" pattern that enforces required fields at compile time (e.g., `builder("id").displayName("name").baseUrl(uri).build()` where `displayName` and `baseUrl` must be called before `build()` is available). This is more type-safe but significantly more complex to implement and read.

### Trade-offs

The builder is roughly 110 lines of straightforward code. It duplicates the field declarations from the record. Any new field added to the record requires adding a field, a setter, and an entry in the `build()` method to the builder. This is mechanical but easy to forget. The ArchUnit tests do not catch this kind of inconsistency -- it would require a separate test that verifies all record components have corresponding builder methods.

The builder does not validate eagerly. If you call `builder("id").build()` without setting `baseUrl`, the error comes from the record's compact constructor, not from the builder. The error message ("Base URL cannot be null") is clear, but the stack trace points to the record constructor rather than the builder callsite. This is a minor debuggability cost.

---

## Putting It All Together: How the Layers Compose

To see how these patterns compose, follow a request through the system:

1. A request arrives at `GatewayResource` (adapter/in/rest) -- a JAX-RS endpoint.
2. Before reaching the resource, `RateLimitFilter` (system/filter) checks rate limits by calling `RateLimiter` (core/port/out) and `ServiceRegistry` (core/service/routing). The filter lives in the system layer and is allowed to depend on both core and adapter.
3. `GatewayResource` calls `GatewayUseCase.forward()` (core/port/in), which is implemented by `GatewayService` (core/service/gateway).
4. `GatewayService` uses `ServiceRegistry` to find a route (returning a `RouteLookupResult` sealed interface), authenticates via `RouteAuthenticationService` (returning a `RouteAuthResult` sealed interface), and forwards via `ProxyClient` (core/port/out).
5. `ProxyClient` is implemented by `ProxyHttpClient` (adapter/out/http), which uses Vert.x's HTTP client.
6. The result is a `GatewayResult` (sealed interface) that the resource maps to an HTTP response.

At no point does the core layer know about Vert.x, Quarkus, Cassandra, or Redis. The ArchUnit tests guarantee this. The `ConfigProducer` bridges configuration. Sealed interfaces make every outcome explicit. Records make every domain object immutable. The builder makes construction ergonomic.

This is what dependency discipline looks like in practice: not a diagram, but a set of interlocking patterns that are tested, enforced, and visible in the code.
