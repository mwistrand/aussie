# Appendix A: Package Map

This appendix is a complete inventory of every package under `api/src/main/java/aussie/`, its role in the hexagonal architecture, its key classes, and its allowed dependencies. Use it as a lookup reference when navigating the codebase or reviewing a pull request that introduces a new import.

All file paths are relative to `api/src/main/java/aussie/`.

---

## Dependency Flow

The fundamental rule is **inward-only**: adapters depend on core and SPI; core depends on nothing outside itself; SPI depends on core port interfaces and core models. No package may introduce a circular dependency. The following ASCII diagram captures the allowed dependency arrows:

```
                         +--------------------------+
                         |       system/filter      |
                         |  (cross-cutting filters) |
                         +-----+----------+---------+
                               |          |
              depends on core  |          | depends on adapter
              services, ports  |          | (problem, telemetry)
                               v          v
     +----------------+   +--------+   +-------------------+
     |  adapter/in    |   |  core  |   |   adapter/out     |
     |  (driving)     |-->|        |<--|   (driven)        |
     +----------------+   +--------+   +-------------------+
            |                 ^                  |
            |     depends on  |                  |
            +--------+--------+                  |
                     |                           |
                     v                           v
               +-----------+              +-----------+
               |    spi    |              |    spi    |
               +-----------+              +-----------+
```

Spelled out as rules:

| Source Layer | May Depend On |
|---|---|
| `core/model` | Only `java.*`, `jakarta.validation` annotations. No other aussie packages. |
| `core/config` | `core/model` (for types used in config interfaces). |
| `core/cache` | Nothing in aussie (generic utility). |
| `core/port/in` | `core/model`. |
| `core/port/out` | `core/model`. |
| `core/service` | `core/model`, `core/port/in`, `core/port/out`, `core/config`, `core/cache`, `core/util`. Never adapters. |
| `core/util` | Nothing in aussie. |
| `spi` | `core/port/out`, `core/model`. Never adapters or core/service. |
| `adapter/in` | `core/port/in`, `core/model`, `core/service` (for direct injection), `spi`, other `adapter` packages. |
| `adapter/out` | `core/port/out`, `core/model`, `core/config`, `core/service` (sparingly), `spi`. |
| `system/filter` | `core/service`, `core/model`, `core/config`, `core/port/out`, `core/util`, `spi`, `adapter/in/problem`, `adapter/out/telemetry`. |

---

## Layer 1: Core -- Model

Pure domain objects. Records, sealed interfaces, enums, value types. No framework annotations. No CDI. No Vert.x. These compile with nothing but the JDK and `jakarta.validation` on the classpath.

### `core/model/auth`

**Role:** Authentication, authorization, and token domain model.

| Class | Description |
|---|---|
| `AccessControlConfig` | Interface defining IP allowlist/denylist and default-deny settings. |
| `ApiKey` | Record representing a stored API key with ID, hash, permissions, and metadata. |
| `ApiKeyCreateResult` | Record returned after key creation; carries the one-time plaintext key. |
| `AussieToken` | Record representing a signed gateway token forwarded to backend services. |
| `AuthenticationContext` | Record holding the authenticated principal, permissions, and auth method. |
| `AuthenticationResult` | Sealed interface with `Success`, `Failure`, and `Skip` variants. |
| `ClaimTranslator` | Interface for transforming token claims during translation. |
| `GatewaySecurityConfig` | Interface for gateway-wide security settings (SSRF protection, header stripping). |
| `KeyStatus` | Enum for signing key lifecycle: `PENDING`, `ACTIVE`, `DEPRECATED`, `RETIRED`. |
| `OidcTokenExchangeRequest` | Record encapsulating parameters for an OAuth2 authorization code exchange. |
| `OidcTokenExchangeResponse` | Record with tokens returned from an OIDC token endpoint. |
| `OperationPermission` | Record pairing an HTTP method pattern with a required permission string. |
| `Permission` | Constants and utilities for the permission string format (`service.scope:action`). |
| `Principal` | Record representing an authenticated identity (subject, issuer, claims). |
| `RevocationEvent` | Sealed interface for JTI revocation and user-level revocation events. |
| `Role` | Record representing an RBAC role with ID, display name, and permission set. |
| `RoleMapping` | Record holding the complete role-to-permissions map for token expansion. |
| `ServiceAccessConfig` | Record defining per-service access control (IP rules, visibility rules). |
| `ServicePermissionPolicy` | Record defining a service's permission enforcement policy. |
| `SigningKeyRecord` | Record for a signing key with key material, status, and lifecycle timestamps. |
| `TokenProviderConfig` | Record providing JWKS URI, issuer, audience for token validation. |
| `TokenValidationResult` | Sealed interface with `Valid`, `Invalid`, and `NoToken` variants. |
| `TranslatedClaims` | Record carrying roles, permissions, and attributes after token translation. |
| `TranslationConfigSchema` | Record defining the schema for claim translation configuration. |
| `TranslationConfigVersion` | Record representing a versioned translation configuration with activation status. |
| `TranslationOutcome` | Enum for translation results: `SUCCESS`, `ERROR`, `FALLBACK`. |
| `VisibilityRule` | Record defining path-level visibility overrides per endpoint. |

**Allowed dependencies:** `java.*`, `jakarta.validation`. No other aussie packages.

---

### `core/model/common`

**Role:** Cross-cutting value types and configuration interfaces shared across subdomains.

| Class | Description |
|---|---|
| `BootstrapResult` | Record returned after bootstrap key creation with key ID and expiration. |
| `CorsConfig` | Interface defining CORS settings (origins, methods, headers, credentials). |
| `JwsConfig` | Interface for JWS token issuance settings (issuer, audience, TTL). |
| `LimitsConfig` | Interface for request size limits (max body, max header size, max headers). |
| `SourceIdentifier` | Record pairing a client IP with an optional API key prefix for rate limit keying. |
| `StorageHealth` | Record for storage backend health status reporting. |
| `TrustedProxyConfig` | Interface defining trusted proxy CIDR ranges for `X-Forwarded-For` parsing. |
| `ValidationResult` | Sealed interface with `Valid` and `Invalid` variants for input validation. |

**Allowed dependencies:** `java.*`. No other aussie packages.

---

### `core/model/gateway`

**Role:** Request/response types for the proxy pipeline.

| Class | Description |
|---|---|
| `GatewayRequest` | Record encapsulating an inbound HTTP request (method, path, headers, body, client IP). |
| `GatewayResult` | Sealed interface with `Success`, `RouteNotFound`, `ServiceNotFound`, `ReservedPath`, `Error`, `Unauthorized`, `Forbidden`, and `BadRequest` variants. |
| `PreparedProxyRequest` | Record with the fully assembled upstream request (URI, headers, body) ready for the `ProxyClient`. |
| `ProxyResponse` | Record wrapping the upstream response (status, headers, body). |
| `RouteAuthResult` | Record combining authentication outcome with the matched route context. |

**Allowed dependencies:** `java.*`. No other aussie packages.

---

### `core/model/ratelimit`

**Role:** Rate limiting domain model including algorithms, keys, and decisions.

| Class | Description |
|---|---|
| `AlgorithmRegistry` | Maps `RateLimitAlgorithm` enum values to their handler implementations. |
| `BucketAlgorithm` | Token bucket algorithm handler implementing `RateLimitAlgorithmHandler`. |
| `BucketState` | Record holding current token count and last refill timestamp for a bucket. |
| `EffectiveRateLimit` | Record combining resolved requests-per-window, window duration, and burst capacity. |
| `EndpointRateLimitConfig` | Record for per-endpoint rate limit overrides registered by service teams. |
| `MessageRateLimitHandler` | Functional interface for WebSocket message-level rate limit callbacks. |
| `RateLimitAlgorithm` | Enum: `BUCKET`, `FIXED_WINDOW`, `SLIDING_WINDOW`. |
| `RateLimitAlgorithmHandler` | Interface for pluggable algorithm implementations. |
| `RateLimitDecision` | Record indicating allowed/denied with remaining tokens and reset time. |
| `RateLimitKey` | Record composing the rate limit bucket key from source identifier, service, and endpoint. |
| `RateLimitKeyType` | Enum: `IP`, `API_KEY`, `SERVICE`, `ENDPOINT`, `WEBSOCKET_CONNECTION`, `WEBSOCKET_MESSAGE`. |
| `RateLimitState` | Record for serialized rate limit state in distributed backends. |
| `ServiceRateLimitConfig` | Record for service-level rate limit configuration. |
| `ServiceWebSocketRateLimitConfig` | Record for per-service WebSocket rate limit configuration. |

**Allowed dependencies:** `java.*`. No other aussie packages.

---

### `core/model/routing`

**Role:** Route matching and endpoint configuration types.

| Class | Description |
|---|---|
| `EndpointConfig` | Record defining a single endpoint (path pattern, HTTP methods, visibility, auth requirements). |
| `EndpointType` | Enum: `REST`, `WEBSOCKET`. |
| `EndpointVisibility` | Enum: `PUBLIC`, `PRIVATE`. |
| `RouteLookupResult` | Sealed interface with `RouteFound`, `ServiceOnly`, and `NotFound` variants. |
| `RouteMatch` | Record pairing a `ServiceRegistration` with the matched `EndpointConfig` and extracted path variables. |
| `ServiceOnlyMatch` | Record for when a service is found but no endpoint pattern matches. |

**Allowed dependencies:** `core/model/service`, `core/model/auth`. No adapter or framework types.

---

### `core/model/sampling`

**Role:** OpenTelemetry trace sampling configuration model.

| Class | Description |
|---|---|
| `EffectiveSamplingRate` | Record with the resolved sampling rate and its source (platform default, service, endpoint). |
| `EndpointSamplingConfig` | Record for per-endpoint sampling rate overrides. |
| `ServiceSamplingConfig` | Record for service-level sampling configuration with endpoint overrides. |

**Allowed dependencies:** `java.*`. No other aussie packages.

---

### `core/model/service`

**Role:** Service registration domain model.

| Class | Description |
|---|---|
| `RegistrationResult` | Sealed interface with `Success` and `Failure` variants. |
| `ServicePath` | Record parsing a request path into service ID, subpath, and gateway-mode flag. |
| `ServiceRegistration` | Record defining a backend service: ID, base URL, endpoints, rate limits, CORS, permissions, sampling, and access control. Central aggregate of the routing domain. |

**Allowed dependencies:** `core/model/auth`, `core/model/common`, `core/model/ratelimit`, `core/model/routing`, `core/model/sampling`.

---

### `core/model/session`

**Role:** Session management domain model.

| Class | Description |
|---|---|
| `Session` | Record representing a user session (ID, user, issuer, claims, timestamps, metadata). |
| `SessionInvalidatedEvent` | CDI event record fired when a session is invalidated. |
| `SessionToken` | Record for the session token returned to clients (session ID + HMAC signature). |

**Allowed dependencies:** `java.*`. No other aussie packages.

---

### `core/model/websocket`

**Role:** WebSocket proxy domain model.

| Class | Description |
|---|---|
| `WebSocketProxySession` | Record tracking an active WebSocket proxy session (client socket, backend socket, service). |
| `WebSocketUpgradeRequest` | Record encapsulating a WebSocket upgrade request with auth headers and target path. |
| `WebSocketUpgradeResult` | Sealed interface with `Accepted` and `Rejected` variants. |

**Allowed dependencies:** `core/model/service`, `core/model/routing`.

---

## Layer 1: Core -- Config

Framework-agnostic configuration interfaces. Use `@ConfigMapping` from SmallRye Config, which is the one permitted framework dependency in core/config. These interfaces are produced as CDI beans by `ConfigProducer` in the adapter layer.

### `core/config`

| Class | Description |
|---|---|
| `ApiKeyConfig` | API key authentication settings (min length, max TTL, hash algorithm). |
| `AuthRateLimitConfig` | Brute-force protection settings (max attempts, lockout duration, progressive lockout). |
| `BootstrapConfig` | Bootstrap key settings (enabled, recovery mode, key value, TTL). |
| `KeyRotationConfig` | Signing key rotation schedule (rotation interval, overlap period, max keys). |
| `OidcConfig` | OIDC/OAuth2 settings (client ID, secret, endpoints, scopes, PKCE). |
| `PkceConfig` | PKCE-specific settings (challenge method, challenge TTL). |
| `RateLimitingConfig` | Rate limiting settings (algorithm, defaults, platform max, WebSocket sub-config). |
| `ResiliencyConfig` | Timeout and bulkhead settings for HTTP proxy, JWKS, Cassandra, and Redis. |
| `RouteAuthConfig` | Per-route authentication settings (default visibility, JWS issuance). |
| `SamplingConfig` | OTel sampling defaults (platform rate, decision caching). |
| `SessionConfig` | Session management settings (TTL, idle timeout, sliding expiration, max sessions). |
| `TokenRevocationConfig` | Token revocation settings (bloom filter size, rebuild interval, hash functions). |
| `TokenTranslationConfig` | Token translation settings (enabled, provider name, cache TTL). |
| `WebSocketConfig` | WebSocket proxy settings (max connections, idle timeout, ping interval). |

**Allowed dependencies:** `core/model` (for enum and config interface types referenced in config hierarchies).

---

## Layer 1: Core -- Cache

Generic in-memory caching abstraction. No CDI annotations. No awareness of what is being cached.

### `core/cache`

| Class | Description |
|---|---|
| `LocalCache<K, V>` | Generic cache interface with `get`, `put`, `invalidate`, `invalidateAll`, `values`, `estimatedSize`. |
| `CaffeineLocalCache<K, V>` | Caffeine-backed implementation with TTL jitter to prevent cross-instance refresh storms. |
| `LocalCacheConfig` | `@ConfigMapping` interface for cache TTLs, max entries, and jitter factor. |

**Allowed dependencies:** Caffeine library. No other aussie packages.

---

## Layer 1: Core -- Util

Stateless utility classes.

### `core/util`

| Class | Description |
|---|---|
| `SecureHash` | SHA-256 hashing utility for rate limit keys, logging, and security events. Truncates to configurable hex length. |

**Allowed dependencies:** `java.*` only.

---

## Layer 1: Core -- Ports (Inbound)

Inbound ports define use cases the application offers. Implemented by core services. Called by driving adapters.

### `core/port/in`

| Interface | Description |
|---|---|
| `GatewayUseCase` | Forward a request via route pattern matching. Single method: `Uni<GatewayResult> forward(GatewayRequest)`. |
| `PassThroughUseCase` | Forward a request directly by service ID. Single method: `Uni<GatewayResult> forward(String serviceId, GatewayRequest)`. |
| `WebSocketGatewayUseCase` | Handle WebSocket upgrade requests for gateway and pass-through modes. |
| `ApiKeyManagement` | CRUD for API keys: create, validate, list, revoke, get. |
| `BootstrapManagement` | Bootstrap admin key creation and state checking. |
| `RoleManagement` | RBAC role CRUD, role-to-permission expansion. |
| `SessionManagement` | Session lifecycle: create, get, refresh, invalidate, invalidate-all-for-user. |

**Allowed dependencies:** `core/model` only.

---

## Layer 1: Core -- Ports (Outbound)

Outbound ports define what core needs from infrastructure. Implemented by driven adapters or loaded via SPI.

### `core/port/out`

| Interface | Description |
|---|---|
| `ApiKeyRepository` | Persistent API key storage (save, findById, findByHash, delete, findAll). |
| `AuthKeyCache` | Caching layer for API key lookups by hash (get, put, invalidate). |
| `ConfigurationCache` | Optional caching layer for `ServiceRegistration` objects. |
| `ForwardedHeaderBuilder` | Build `X-Forwarded-*` or RFC 7239 `Forwarded` headers for proxied requests. |
| `ForwardedHeaderBuilderProvider` | Factory selecting which header format to use. |
| `JwksCache` | Fetch, cache, and refresh JSON Web Key Sets from identity providers. |
| `Metrics` | Record gateway metrics: requests, latency, traffic, errors, auth, WebSocket, rate limits, timeouts. |
| `OidcRefreshTokenRepository` | Store and retrieve OIDC refresh tokens keyed by session ID. |
| `PkceChallengeRepository` | Store and atomically consume PKCE challenges. |
| `ProxyClient` | Forward a `PreparedProxyRequest` to the upstream service. Single method: `Uni<ProxyResponse> forward(PreparedProxyRequest)`. |
| `RateLimiter` | Check-and-consume rate limit tokens, get status, reset, remove keys. |
| `RevocationEventPublisher` | Publish and subscribe to token revocation events across instances. |
| `RoleRepository` | Persistent role storage with role mapping retrieval. |
| `SamplingConfigRepository` | Look up per-service OTel sampling configuration. |
| `SecurityMonitoring` | Record requests, auth failures, and access denials for anomaly detection. |
| `ServiceRegistrationRepository` | Persistent storage for service registrations (save, findById, delete, findAll, count). |
| `SessionRepository` | Session storage with atomic insert-if-absent semantics. |
| `StorageHealthIndicator` | Optional health check for storage backends. |
| `TrafficAttributing` | Record attributed traffic for billing and cost allocation. |
| `TranslationConfigCache` | Distributed cache for translation configuration versions. |
| `TranslationConfigRepository` | Persistent storage for versioned translation configurations. |
| `TranslationMetrics` | Record token translation metrics (cache hits, errors, durations). |

**Allowed dependencies:** `core/model` only.

---

## Layer 1: Core -- Services

Domain services implementing inbound ports and composing outbound ports. All are `@ApplicationScoped` CDI beans with constructor injection. All return `Uni` or `Multi` -- no blocking.

### `core/service/auth`

**Role:** Authentication, authorization, token management, and RBAC services.

| Class | Description |
|---|---|
| `AccessControlEvaluator` | Evaluates IP allowlists/denylists and visibility rules against a request. |
| `ApiKeyEncryptionService` | Handles API key hashing (SHA-256) for storage and comparison. |
| `ApiKeyService` | Implements `ApiKeyManagement`. Key CRUD with cache integration. |
| `AuthRateLimitService` | Brute-force protection: tracks failed attempts, applies progressive lockout. |
| `DefaultPermissionPolicy` | Default implementation of service-level permission evaluation. |
| `JwksCacheService` | Implements `JwksCache`. Fetches and caches JWKS with request coalescing to prevent thundering herd. |
| `KeyRotationService` | Automated signing key rotation lifecycle (generate, activate, deprecate, retire). |
| `OidcTokenExchangeProviderRegistry` | Selects the active `OidcTokenExchangeProvider` by configuration or priority. |
| `PkceService` | PKCE challenge generation and verification. |
| `PkceStorageProviderRegistry` | Selects the active `PkceStorageProvider`. |
| `RevocationBloomFilter` | Probabilistic check for revoked JTI/user with periodic rebuild from persistent store. |
| `RevocationCache` | Two-tier cache: bloom filter (fast reject) backed by persistent `TokenRevocationRepository`. |
| `RoleEncryptionService` | Placeholder for role data encryption at rest. |
| `RoleService` | Implements `RoleManagement`. Role CRUD and permission expansion. |
| `ServiceAuthorizationService` | Evaluates per-service permission policies against authenticated principals. |
| `SigningKeyRegistry` | Manages the active signing key set for JWKS endpoint and token issuance. |
| `TokenIssuanceService` | Coordinates `TokenIssuerProvider` to sign gateway tokens. |
| `TokenRevocationService` | Revoke tokens by JTI or by user with bloom filter integration and event publishing. |
| `TokenTranslationService` | Translate external IdP claims to Aussie roles/permissions via `TokenTranslatorProvider`. |
| `TokenTranslatorProviderRegistry` | Selects the active `TokenTranslatorProvider`. |
| `TokenValidationService` | Validate bearer tokens via `TokenValidatorProvider` with revocation checking. |
| `TranslationConfigService` | Versioned translation configuration management with tiered caching. |

**Allowed dependencies:** `core/model`, `core/port/out`, `core/config`, `core/cache`, `core/util`, `spi` (for provider types).

---

### `core/service/common`

**Role:** Cross-cutting services shared across subdomains.

| Class | Description |
|---|---|
| `BootstrapService` | Implements `BootstrapManagement`. Creates time-limited admin keys on first startup. |
| `ClientIpExtractor` | Extracts real client IP from `X-Forwarded-For` or socket address, respecting trusted proxies. |
| `RequestSizeValidator` | Validates request body size and header sizes against `LimitsConfig`. |
| `SourceIdentifierExtractor` | Composes a `SourceIdentifier` from client IP and optional API key prefix for rate limit keying. |
| `TrustedProxyValidator` | Validates whether a source IP falls within configured trusted proxy CIDR ranges. |

**Allowed dependencies:** `core/model`, `core/config`.

---

### `core/service/gateway`

**Role:** The proxy pipeline -- the central request path.

| Class | Description |
|---|---|
| `GatewayService` | Implements `GatewayUseCase`. Coordinates route matching, authentication, request preparation, and proxying. |
| `PassThroughService` | Implements `PassThroughUseCase`. Direct service-ID-based forwarding without pattern matching. |
| `ProxyRequestPreparer` | Assembles `PreparedProxyRequest` from the matched route, original request, and forwarded headers. Strips hop-by-hop headers, injects `X-Aussie-*` metadata headers. |
| `RouteAuthenticationService` | Evaluates per-route auth requirements and issues gateway tokens for authenticated routes. |
| `WebSocketGatewayService` | Implements `WebSocketGatewayUseCase`. Validates WebSocket upgrade requests against route auth and connection limits. |

**Allowed dependencies:** `core/model`, `core/port/in`, `core/port/out`, `core/service/routing`, `core/service/auth`.

---

### `core/service/ratelimit`

**Role:** Rate limit resolution logic.

| Class | Description |
|---|---|
| `RateLimitResolver` | Resolves the `EffectiveRateLimit` for a request by merging platform defaults with service and endpoint overrides. Enforces platform ceiling. |
| `WebSocketRateLimitService` | Rate limiting for WebSocket connection establishment and per-connection message throughput. |

**Allowed dependencies:** `core/model`, `core/config`, `core/port/out`.

---

### `core/service/routing`

**Role:** Service registration management and route matching.

| Class | Description |
|---|---|
| `EndpointMatcher` | Compiles endpoint path patterns into regex matchers, resolves the best match for a request path. |
| `GlobPatternMatcher` | Converts glob patterns (`/users/*/orders`) into Java regex patterns. |
| `ServiceRegistrationValidator` | Validates registration requests: service ID format, base URL (SSRF protection), endpoint uniqueness, permission policy coherence. |
| `ServiceRegistry` | Central coordinator. Manages in-memory route cache (Caffeine-backed), delegates to `ServiceRegistrationRepository` for persistence and `ConfigurationCache` for optional distributed caching. Handles authorization for service operations. |
| `VisibilityResolver` | Resolves effective visibility for an endpoint by combining service-level defaults with endpoint-level and visibility rule overrides. |

**Allowed dependencies:** `core/model`, `core/port/out`, `core/cache`, `core/service/auth`.

---

### `core/service/session`

**Role:** Session lifecycle management.

| Class | Description |
|---|---|
| `SessionFeatureFlag` | Checks whether session management is enabled via configuration. |
| `SessionIdGenerator` | Generates cryptographically random session IDs with configurable length. |
| `SessionService` | Implements `SessionManagement`. Session create (with collision retry), get (with expiry check), refresh (sliding window), invalidate. |
| `SessionStorageProviderRegistry` | Selects the active `SessionStorageProvider` by configuration or priority. |
| `SessionTokenService` | Creates and validates HMAC-signed session tokens. |

**Allowed dependencies:** `core/model`, `core/port/out`, `core/config`, `spi`.

---

## Layer 2: SPI -- Extension Points

The `spi` package contains interfaces that platform teams implement to extend the gateway. Implementations are discovered via `java.util.ServiceLoader` (for storage/rate-limiting) or CDI `@Alternative` beans (for auth/security). Each interface follows the provider pattern: `name()`, `priority()`, `isAvailable()`, and a factory method.

### `spi`

| Interface | Factory Method | Produces | Discovery |
|---|---|---|---|
| `AuthenticationProvider` | `authenticate(headers, path)` | `AuthenticationResult` | CDI bean |
| `AuthKeyCacheProvider` | `createCache(config)` | `AuthKeyCache` | ServiceLoader |
| `AuthKeyStorageProvider` | `createRepository(config)` | `ApiKeyRepository` | ServiceLoader |
| `ConfigurationCacheProvider` | `createCache(config)` | `ConfigurationCache` | ServiceLoader |
| `FailedAttemptRepository` | *(is the repository itself)* | -- | CDI `@Alternative` |
| `OidcTokenExchangeProvider` | `exchange(request)` | `OidcTokenExchangeResponse` | CDI bean |
| `PkceStorageProvider` | `createRepository()` | `PkceChallengeRepository` | CDI bean |
| `RateLimiterProvider` | `createRateLimiter()` | `RateLimiter` | ServiceLoader |
| `RoleStorageProvider` | `createRepository(config)` | `RoleRepository` | ServiceLoader |
| `SamplingConfigProvider` | `createRepository(config)` | `SamplingConfigRepository` | ServiceLoader |
| `SecurityEvent` | *(sealed data type, not a provider)* | -- | -- |
| `SecurityEventHandler` | `handle(event)` | -- | ServiceLoader |
| `SessionStorageProvider` | `createRepository()` | `SessionRepository` | CDI bean |
| `SigningKeyRepository` | *(is the repository itself)* | -- | CDI `@Alternative` |
| `StorageAdapterConfig` | *(config access interface)* | -- | -- |
| `StorageProviderException` | *(exception class)* | -- | -- |
| `StorageRepositoryProvider` | `createRepository(config)` | `ServiceRegistrationRepository` | ServiceLoader |
| `TokenIssuerProvider` | `issue(validated, config)` | `AussieToken` | CDI bean |
| `TokenRevocationRepository` | *(is the repository itself)* | -- | CDI `@Alternative` |
| `TokenTranslatorProvider` | `translate(issuer, subject, claims)` | `TranslatedClaims` | CDI bean |
| `TokenValidatorProvider` | `validate(token, config)` | `TokenValidationResult` | CDI bean |
| `TranslationConfigCacheProvider` | `createCache(config)` | `TranslationConfigCache` | ServiceLoader |
| `TranslationConfigStorageProvider` | `createRepository(config)` | `TranslationConfigRepository` | ServiceLoader |

`SecurityEvent` is a sealed interface, not a provider. It defines seven event types (`AuthenticationFailure`, `AuthenticationLockout`, `AccessDenied`, `RateLimitExceeded`, `SuspiciousPattern`, `DosAttackDetected`, `SessionInvalidated`) with severity levels computed from event data.

**Allowed dependencies:** `core/port/out`, `core/model`. Never `core/service` or `adapter`.

---

## Layer 3: Adapter/In -- Driving Adapters

These packages translate HTTP, WebSocket, and lifecycle events into calls on core ports and services.

### `adapter/in/auth`

**Role:** Quarkus Security integration. Authenticates inbound requests using the Quarkus `HttpAuthenticationMechanism` and `IdentityProvider` contracts.

| Class | Description |
|---|---|
| `ApiKeyAuthenticationMechanism` | Extracts `X-API-Key` header and delegates to `ApiKeyIdentityProvider`. |
| `ApiKeyAuthenticationRequest` | Quarkus security credential wrapping a plaintext API key. |
| `ApiKeyAuthProvider` | Implements `AuthenticationProvider` SPI for API key validation (priority 100). |
| `ApiKeyIdentityProvider` | Quarkus `IdentityProvider` that validates API keys via `ApiKeyManagement`. |
| `ConflictingAuthFilter` | Detects and rejects requests with both API key and Bearer token. |
| `JwtAuthenticationMechanism` | Extracts Bearer token and delegates to `JwtIdentityProvider`. |
| `JwtAuthenticationRequest` | Quarkus security credential wrapping a JWT. |
| `JwtIdentityProvider` | Quarkus `IdentityProvider` that validates JWTs via `TokenValidationService`. |
| `NoopAuthGuard` | Startup guard that prevents `dangerous-noop` auth mode in production profiles. |
| `NoopAuthProvider` | Development-only auth provider that allows all requests (priority `Integer.MIN_VALUE`). |
| `SessionAuthenticationMechanism` | Extracts session cookie and validates via `SessionManagement`. |
| `SessionCookieManager` | Manages session cookie creation and extraction with SameSite and Secure flags. |

**Allowed dependencies:** `core/port/in`, `core/model/auth`, `core/service/auth`, `spi`.

---

### `adapter/in/bootstrap`

**Role:** Application startup lifecycle hooks.

| Class | Description |
|---|---|
| `BootstrapInitializer` | Observes Quarkus `StartupEvent`. Triggers bootstrap admin key creation if enabled and no admin keys exist. |

**Allowed dependencies:** `core/port/in`, `core/config`.

---

### `adapter/in/dto`

**Role:** Request and response DTOs for the REST API. Decouples HTTP serialization from domain model.

| Class | Description |
|---|---|
| `CorsConfigDto` | DTO for CORS configuration in service registration requests. |
| `CreateApiKeyRequest` | DTO for API key creation requests. |
| `CreateRoleRequest` | DTO for role creation requests. |
| `EndpointConfigDto` | DTO for endpoint configuration in service registration. |
| `OperationPermissionDto` | DTO for method-level permission mappings. |
| `ServiceAccessConfigDto` | DTO for per-service access control settings. |
| `ServicePermissionPolicyDto` | DTO for service permission policy configuration. |
| `ServiceRateLimitConfigDto` | DTO for service rate limit configuration. |
| `ServiceRegistrationRequest` | DTO for the full service registration payload. |
| `ServiceRegistrationResponse` | DTO for service registration responses. |
| `ServiceSamplingConfigDto` | DTO for per-service sampling configuration. |
| `TranslationConfigUploadDto` | DTO for uploading translation configurations. |
| `TranslationConfigValidationDto` | DTO for translation config validation results. |
| `TranslationConfigVersionDto` | DTO for translation config version details. |
| `TranslationConfigVersionSummaryDto` | DTO for translation config version list items. |
| `TranslationStatusDto` | DTO for translation service status. |
| `TranslationTestRequestDto` | DTO for testing translation configurations. |
| `TranslationTestResultDto` | DTO for translation test results. |
| `UpdateRoleRequest` | DTO for role update requests. |
| `VisibilityRuleDto` | DTO for visibility rule configuration. |

**Allowed dependencies:** `core/model` (for mapping to/from domain types).

---

### `adapter/in/health`

**Role:** MicroProfile Health check endpoints.

| Class | Description |
|---|---|
| `BulkheadHealthCheck` | Readiness check reporting configured bulkhead limits for Cassandra, Redis, HTTP proxy, and JWKS. Always UP -- transient pressure is handled by metrics, not health. |

**Allowed dependencies:** `adapter/out/telemetry` (for `BulkheadMetrics`).

---

### `adapter/in/http`

**Role:** Vert.x-level HTTP filters and HTTP-specific resources that run before JAX-RS.

| Class | Description |
|---|---|
| `CorsFilter` | `@RouteFilter(100)`. Handles CORS preflight and adds CORS response headers at the Vert.x level. |
| `GatewayCorsConfig` | `@ConfigMapping` for global CORS settings. |
| `JwksResource` | Serves the `/.well-known/jwks.json` endpoint with the gateway's public signing keys. |
| `OidcResource` | Handles OIDC callback (`/auth/callback`), login redirect, and token exchange. |
| `SecurityHeadersConfig` | `@ConfigMapping` for security response headers (CSP, HSTS, X-Frame-Options, etc.). |
| `SecurityHeadersFilter` | `@RouteFilter(90)`. Adds OWASP-recommended security headers to all responses. |
| `SessionResource` | Session management endpoints (login, logout, refresh, introspect). |

**Allowed dependencies:** `core/model`, `core/port/in`, `core/service/auth`, `core/config`.

---

### `adapter/in/problem`

**Role:** RFC 9457 Problem Details error handling.

| Class | Description |
|---|---|
| `GatewayProblem` | Static factory for `HttpProblem` instances: `serviceNotFound`, `routeNotFound`, `rateLimited`, `unauthorized`, `forbidden`, `badGateway`, `payloadTooLarge`, etc. |
| `GlobalExceptionMappers` | `@ServerExceptionMapper` methods converting `JwksFetchException`, `IllegalArgumentException`, and `IllegalStateException` to RFC 9457 responses. |

**Allowed dependencies:** `core/service/auth` (for exception types). Quarkus resteasy-problem.

---

### `adapter/in/rest`

**Role:** JAX-RS resource classes. Thin HTTP wrappers that delegate to core ports and services.

| Class | Description |
|---|---|
| `AdminResource` | `@Path("/admin/services")`. Service registration CRUD. Delegates to `ServiceRegistry`. |
| `ApiKeyResource` | `@Path("/admin/api-keys")`. API key management. Delegates to `ApiKeyManagement`. |
| `AuthResource` | `@Path("/auth")`. Authentication endpoints (login, token exchange, user info). |
| `BenchmarkResource` | `@Path("/admin/benchmark")`. Performance testing endpoints (dev/test only). |
| `GatewayResource` | `@Path("/gateway")`. Gateway-mode proxy for all HTTP methods. Delegates to `GatewayUseCase`. |
| `JacksonCustomizer` | Configures Jackson ObjectMapper settings for the REST API. |
| `LockoutResource` | `@Path("/admin/lockouts")`. View and clear authentication lockouts. |
| `PassThroughResource` | `@Path("/{serviceId}")`. Pass-through proxy for all HTTP methods. Delegates to `PassThroughUseCase`. |
| `RoleResource` | `@Path("/admin/roles")`. RBAC role management. Delegates to `RoleManagement`. |
| `ServicePermissionsResource` | `@Path("/admin/services/{serviceId}/permissions")`. Per-service permission policy management. |
| `SigningKeyResource` | `@Path("/admin/signing-keys")`. Signing key management and rotation trigger. |
| `TokenRevocationResource` | `@Path("/admin/revocations")`. Token revocation endpoints. |
| `TranslationConfigResource` | `@Path("/admin/translation-config")`. Translation configuration version management. |

**Allowed dependencies:** `core/port/in`, `core/service` (for `ServiceRegistry` and other services), `adapter/in/dto`, `adapter/in/problem`.

---

### `adapter/in/validation`

**Role:** Input validation utilities.

| Class | Description |
|---|---|
| `UrlValidator` | SSRF-safe URL validation for service base URLs. Rejects internal IPs, metadata endpoints, non-HTTP schemes. |

**Allowed dependencies:** `adapter/in/problem`.

---

### `adapter/in/websocket`

**Role:** WebSocket proxy adapter using Vert.x native WebSocket handling.

| Class | Description |
|---|---|
| `WebSocketGateway` | Manages WebSocket proxy sessions. Bi-directional frame forwarding between client and upstream. Handles session invalidation events for forced disconnect. |
| `WebSocketRateLimitFilter` | `@RouteFilter(50)`. Applies connection-level rate limiting before WebSocket upgrade. |
| `WebSocketUpgradeFilter` | `@RouteFilter(50)`. Intercepts WebSocket upgrade requests and routes them to `WebSocketGateway`. |

**Allowed dependencies:** `core/port/in`, `core/model/websocket`, `core/service/ratelimit`, `core/config`, `adapter/out/telemetry`.

---

## Layer 3: Adapter/Out -- Driven Adapters

These packages implement outbound ports with concrete infrastructure.

### `adapter/out/auth`

**Role:** Authentication infrastructure: signing keys, token translation, OIDC validation.

| Class | Description |
|---|---|
| `ConfigSigningKeyRepository` | Implements `SigningKeyRepository`. Stores signing keys in application configuration (suitable for single-instance or dev). |
| `ConfigTokenTranslatorProvider` | Implements `TokenTranslatorProvider`. Config-driven claim translation using `TranslationConfigSchema`. |
| `DefaultOidcTokenExchangeProvider` | Implements `OidcTokenExchangeProvider`. Standard OAuth2 code-for-token exchange via HTTP. |
| `DefaultTokenTranslatorProvider` | Implements `TokenTranslatorProvider`. Extracts from standard `roles` and `permissions` claims (priority 100). |
| `OidcTokenValidator` | Implements `TokenValidatorProvider`. Validates JWTs against JWKS endpoints with issuer and audience checks. |
| `RemoteTokenTranslatorProvider` | Implements `TokenTranslatorProvider`. Delegates translation to a remote service endpoint. |
| `RsaTokenIssuer` | Implements `TokenIssuerProvider`. Signs JWS tokens using RSA-256 with the active signing key. |

**Allowed dependencies:** `core/port/out`, `core/model`, `core/service/auth`, `spi`.

---

### `adapter/out/auth/config`

**Role:** Reserved package for auth-specific configuration. Currently empty.

---

### `adapter/out/http`

**Role:** HTTP proxy infrastructure and configuration bridging.

| Class | Description |
|---|---|
| `ConfigProducer` | CDI producer bridging `GatewayConfig` sub-interfaces to injectable core config types (`LimitsConfig`, `AccessControlConfig`, `GatewaySecurityConfig`, `TrustedProxyConfig`). |
| `ForwardedHeaderBuilderFactory` | Implements `ForwardedHeaderBuilderProvider`. Selects between `X-Forwarded-*` and RFC 7239 based on configuration. |
| `ForwardingConfig` | `@ConfigMapping` sub-interface for forwarded header style and stripping settings. |
| `GatewayConfig` | `@ConfigMapping(prefix = "aussie.gateway")`. Top-level gateway configuration aggregating forwarding, limits, access control, security, and trusted proxy settings. |
| `ProxyHttpClient` | Implements `ProxyClient`. Uses Vert.x `WebClient` for non-blocking upstream requests with OpenTelemetry trace propagation. |
| `Rfc7239ForwardedHeaderBuilder` | Implements `ForwardedHeaderBuilder` using RFC 7239 `Forwarded` header format. |
| `SecurityConfig` | `@ConfigMapping` sub-interface implementing `GatewaySecurityConfig`. |
| `XForwardedHeaderBuilder` | Implements `ForwardedHeaderBuilder` using legacy `X-Forwarded-For/Host/Proto` headers. |

**Allowed dependencies:** `core/port/out`, `core/model`, `core/config`, `core/service/gateway` (for `ProxyRequestPreparer`), `adapter/out/telemetry`.

---

### `adapter/out/ratelimit`

**Role:** Rate limiter implementations.

| Class | Description |
|---|---|
| `NoOpRateLimiter` | Implements `RateLimiter`. Always allows. Used when rate limiting is disabled. |
| `RateLimiterProviderLoader` | CDI producer that discovers `RateLimiterProvider` via ServiceLoader and produces the winning `RateLimiter` bean. |

**Subpackage: `adapter/out/ratelimit/memory`**

| Class | Description |
|---|---|
| `InMemoryRateLimiter` | Implements `RateLimiter`. `ConcurrentHashMap`-based with stale entry cleanup. Single-instance only. |
| `InMemoryRateLimiterProvider` | Implements `RateLimiterProvider`. Priority 0 (fallback). Always available. |

**Subpackage: `adapter/out/ratelimit/redis`**

| Class | Description |
|---|---|
| `RedisRateLimiter` | Implements `RateLimiter`. Uses Lua scripts for atomic check-and-consume. Distributed. |
| `RedisRateLimiterProvider` | Implements `RateLimiterProvider`. Priority 10. Available when Redis is configured. |

**Allowed dependencies:** `core/port/out`, `core/model/ratelimit`, `core/config`, `spi`.

---

### `adapter/out/storage`

**Role:** Storage provider loading, initialization, and cross-cutting storage utilities.

| Class | Description |
|---|---|
| `AuthKeyStorageProviderLoader` | CDI producer discovering `AuthKeyStorageProvider` via ServiceLoader. Produces `ApiKeyRepository`. |
| `MicroProfileStorageAdapterConfig` | Implements `StorageAdapterConfig`. Bridges MicroProfile Config to the SPI config interface. |
| `NoOpAuthKeyCache` | Implements `AuthKeyCache`. Pass-through (no caching). Used when auth caching is disabled. |
| `NoOpConfigurationCache` | Implements `ConfigurationCache`. Pass-through. Used when cache provider is absent. |
| `PkceChallengeRepositoryProducer` | CDI producer for `PkceChallengeRepository` via `PkceStorageProviderRegistry`. |
| `RoleStorageProviderLoader` | CDI producer discovering `RoleStorageProvider` via ServiceLoader. Produces `RoleRepository`. |
| `StorageInitializer` | Observes `StartupEvent`. Loads service registrations from persistent storage into the in-memory route cache. |
| `StorageProviderLoader` | CDI producer discovering `StorageRepositoryProvider` and `ConfigurationCacheProvider` via ServiceLoader. |
| `TieredTranslationConfigRepository` | Wraps a primary `TranslationConfigRepository` with an optional `TranslationConfigCache` for L2 caching. |
| `TranslationConfigStorageLoader` | CDI producer for `TranslationConfigRepository` and `TranslationConfigCache` via ServiceLoader. |

**Subpackage: `adapter/out/storage/cassandra`**

| Class | Description |
|---|---|
| `CassandraApiKeyRepository` | Implements `ApiKeyRepository`. Cassandra-backed with prepared statements. |
| `CassandraAuthKeyStorageProvider` | Implements `AuthKeyStorageProvider`. Priority 10. |
| `CassandraMigrationRunner` | Runs CQL schema migration scripts on startup. |
| `CassandraRoleRepository` | Implements `RoleRepository`. Cassandra-backed. |
| `CassandraRoleStorageProvider` | Implements `RoleStorageProvider`. Priority 10. |
| `CassandraServiceRegistrationRepository` | Implements `ServiceRegistrationRepository`. Cassandra-backed. |
| `CassandraStorageProvider` | Implements `StorageRepositoryProvider`. Priority 10. |
| `CassandraTranslationConfigRepository` | Implements `TranslationConfigRepository`. Cassandra-backed. |
| `CassandraTranslationConfigStorageProvider` | Implements `TranslationConfigStorageProvider`. Priority 10. |

**Subpackage: `adapter/out/storage/memory`**

| Class | Description |
|---|---|
| `InMemoryApiKeyRepository` | `ConcurrentHashMap`-backed `ApiKeyRepository`. |
| `InMemoryAuthKeyStorageProvider` | Implements `AuthKeyStorageProvider`. Priority 0. |
| `InMemoryFailedAttemptRepository` | `ConcurrentHashMap`-backed `FailedAttemptRepository` with scheduled cleanup. |
| `InMemoryPkceChallengeRepository` | `ConcurrentHashMap`-backed `PkceChallengeRepository`. |
| `InMemoryPkceStorageProvider` | Implements `PkceStorageProvider`. Priority 0. |
| `InMemoryRevocationEventPublisher` | In-process `RevocationEventPublisher`. Single-instance only. |
| `InMemoryRoleRepository` | `ConcurrentHashMap`-backed `RoleRepository`. |
| `InMemoryRoleStorageProvider` | Implements `RoleStorageProvider`. Priority 0. |
| `InMemorySamplingConfigProvider` | Implements `SamplingConfigProvider`. Priority 0. |
| `InMemorySamplingConfigRepository` | `ConcurrentHashMap`-backed `SamplingConfigRepository`. |
| `InMemoryServiceRegistrationRepository` | `ConcurrentHashMap`-backed `ServiceRegistrationRepository`. |
| `InMemorySessionRepository` | `ConcurrentHashMap`-backed `SessionRepository`. |
| `InMemorySessionStorageProvider` | Implements `SessionStorageProvider`. Priority 0. |
| `InMemoryStorageProvider` | Implements `StorageRepositoryProvider`. Priority 0. Always available. |
| `InMemoryTokenRevocationRepository` | `ConcurrentHashMap`-backed `TokenRevocationRepository`. |
| `InMemoryTranslationConfigRepository` | `ConcurrentHashMap`-backed `TranslationConfigRepository`. |
| `InMemoryTranslationConfigStorageProvider` | Implements `TranslationConfigStorageProvider`. Priority 0. |

**Subpackage: `adapter/out/storage/redis`**

| Class | Description |
|---|---|
| `RedisAuthKeyCache` | Implements `AuthKeyCache`. Redis-backed with TTL. |
| `RedisAuthKeyCacheProvider` | Implements `AuthKeyCacheProvider`. |
| `RedisCacheProvider` | Implements `ConfigurationCacheProvider`. Redis-backed. |
| `RedisConfigurationCache` | Implements `ConfigurationCache`. Caches serialized `ServiceRegistration` in Redis. |
| `RedisFailedAttemptRepository` | Implements `FailedAttemptRepository`. Atomic INCR with TTL. |
| `RedisOidcRefreshTokenRepository` | Implements `OidcRefreshTokenRepository`. |
| `RedisPkceChallengeRepository` | Implements `PkceChallengeRepository`. Atomic GET-and-DELETE via Lua. |
| `RedisPkceStorageProvider` | Implements `PkceStorageProvider`. Priority 100. |
| `RedisRevocationEventPublisher` | Implements `RevocationEventPublisher`. Redis Pub/Sub for cross-instance revocation. |
| `RedisSessionRepository` | Implements `SessionRepository`. Redis-backed with atomic SETNX. |
| `RedisSessionStorageProvider` | Implements `SessionStorageProvider`. Priority 100. |
| `RedisTimeoutHelper` | Utility for applying configured timeouts to Redis operations with fail-open/fail-closed semantics. |
| `RedisTokenRevocationRepository` | Implements `TokenRevocationRepository`. Redis SET with TTL. |
| `RedisTranslationConfigCache` | Implements `TranslationConfigCache`. Redis-backed L2 cache. |
| `RedisTranslationConfigCacheProvider` | Implements `TranslationConfigCacheProvider`. Priority 10. |

**Allowed dependencies:** `core/port/out`, `core/model`, `core/config`, `spi`, `adapter/out/threading`.

---

### `adapter/out/telemetry`

**Role:** Observability infrastructure: metrics, tracing, security event dispatch, sampling.

| Class | Description |
|---|---|
| `BulkheadMetrics` | Reports connection pool configuration as Micrometer gauges. |
| `GatewayMetrics` | Implements `Metrics`. Records all gateway metrics via Micrometer (counters, timers, gauges). |
| `HierarchicalSampler` | OpenTelemetry `Sampler` implementation with per-service and per-endpoint rate overrides. |
| `HierarchicalSamplerProvider` | Registers `HierarchicalSampler` as the OTel sampler. |
| `LoggingSecurityEventHandler` | Implements `SecurityEventHandler`. Structured logging of security events (priority 0). |
| `MetricsSecurityEventHandler` | Implements `SecurityEventHandler`. Records security events as Micrometer metrics (priority 10). |
| `SamplingConfigProviderLoader` | CDI producer discovering `SamplingConfigProvider` via ServiceLoader. |
| `SamplingResolver` | Resolves effective sampling rate for a request from platform defaults, service, and endpoint config. |
| `SamplingResolverHolder` | Static holder for the `SamplingResolver` instance, accessible from the OTel sampler (which runs outside CDI). |
| `SamplingResolverInitializer` | Observes `StartupEvent`. Initializes `SamplingResolverHolder`. |
| `SecurityEventDispatcher` | Dispatches `SecurityEvent` instances to all registered `SecurityEventHandler` implementations. |
| `SecurityMonitor` | Implements `SecurityMonitoring`. Tracks per-client request rates and detects anomalies (DoS, brute force). |
| `SpanAttributes` | Constants for OpenTelemetry span attribute names (`aussie.service.id`, `aussie.route.pattern`, etc.). |
| `TelemetryConfig` | `@ConfigMapping(prefix = "aussie.telemetry")`. Master toggle and sub-configs for tracing, metrics, and security monitoring. |
| `TelemetryFallbackProducer` | CDI producer for no-op telemetry beans when telemetry is disabled. |
| `TelemetryHelper` | Utility for adding attributes to the current OTel span. |
| `TokenTranslationMetrics` | Implements `TranslationMetrics`. Records translation cache hits, durations, and errors. |
| `TrafficAttribution` | Value object for attributed traffic data (team, tenant, cost center). |
| `TrafficAttributionService` | Implements `TrafficAttributing`. Extracts attribution metadata from headers and records via metrics. |

**Allowed dependencies:** `core/port/out`, `core/model`, `core/config`, `spi`.

---

### `adapter/out/threading`

**Role:** Vert.x threading utilities.

| Class | Description |
|---|---|
| `VertxContextHelper` | Provides an `Executor` that emits on the Vert.x event loop. Used by Cassandra and other NIO-based adapters to return results to the correct thread. |

**Allowed dependencies:** Vert.x core, Mutiny. No aussie packages.

---

## Layer 4: System -- Cross-Cutting Filters

JAX-RS server filters that intercept requests before they reach resource classes. These are the only classes permitted to depend on both adapter and core packages simultaneously.

### `system/filter`

| Class | Priority | Description |
|---|---|---|
| `RequestValidationFilter` | `AUTHENTICATION - 100` | Validates request body size and header sizes. Blocks oversized requests with 413/431. |
| `AuthRateLimitFilter` | `AUTHENTICATION - 100` | Checks if the client IP is locked out due to brute-force attempts. Build-time conditional on `aussie.auth.rate-limit.enabled`. |
| `RateLimitFilter` | `@ServerRequestFilter` | Enforces per-service and per-endpoint rate limits. Adds `X-RateLimit-*` response headers. Runs before authentication to reject floods cheaply. |
| `AccessControlFilter` | `@ServerRequestFilter` | Evaluates IP allowlists/denylists and endpoint visibility. Blocks requests to private endpoints and denied IPs. |
| `AuthenticationFilter` | `AUTHENTICATION` | **Deprecated.** Legacy authentication filter retained for backward compatibility with custom `AuthenticationProvider` SPIs. Superseded by Quarkus Security integration (`ApiKeyAuthenticationMechanism`, `JwtAuthenticationMechanism`). Disabled by default. |

**Allowed dependencies:** `core/service`, `core/model`, `core/config`, `core/port/out`, `core/util`, `spi`, `adapter/in/problem`, `adapter/out/telemetry`.

---

## Quick Reference: File Counts by Package

| Package | Files |
|---|---|
| `core/model/auth` | 27 |
| `core/model/common` | 8 |
| `core/model/gateway` | 5 |
| `core/model/ratelimit` | 14 |
| `core/model/routing` | 6 |
| `core/model/sampling` | 3 |
| `core/model/service` | 3 |
| `core/model/session` | 3 |
| `core/model/websocket` | 3 |
| `core/config` | 14 |
| `core/cache` | 3 |
| `core/port/in` | 7 |
| `core/port/out` | 22 |
| `core/service/auth` | 22 |
| `core/service/common` | 5 |
| `core/service/gateway` | 5 |
| `core/service/ratelimit` | 2 |
| `core/service/routing` | 5 |
| `core/service/session` | 5 |
| `core/util` | 1 |
| `spi` | 23 |
| `adapter/in/auth` | 12 |
| `adapter/in/bootstrap` | 1 |
| `adapter/in/dto` | 20 |
| `adapter/in/health` | 1 |
| `adapter/in/http` | 7 |
| `adapter/in/problem` | 2 |
| `adapter/in/rest` | 14 |
| `adapter/in/validation` | 1 |
| `adapter/in/websocket` | 3 |
| `adapter/out/auth` | 7 |
| `adapter/out/http` | 8 |
| `adapter/out/ratelimit` | 2 (+ 2 memory + 2 redis) |
| `adapter/out/storage` | 10 (+ 9 cassandra + 17 memory + 15 redis) |
| `adapter/out/telemetry` | 19 |
| `adapter/out/threading` | 1 |
| `system/filter` | 5 |
| **Total** | **343** |

---

## How to Use This Map

**Adding a new outbound port.** Create the interface in `core/port/out`. It depends only on `core/model` types. Then create the SPI provider interface in `spi/` if external teams need to swap the implementation. Write the default implementation in `adapter/out/`. Write the ServiceLoader or CDI producer in `adapter/out/storage/` or the relevant subpackage. Write the in-memory fallback in `adapter/out/storage/memory/`.

**Adding a new REST endpoint.** Create the DTO in `adapter/in/dto`. Create the resource class in `adapter/in/rest`. The resource depends on a core port or service -- never on adapter/out classes. If the endpoint needs a new use case, define it in `core/port/in` and implement it in `core/service`.

**Adding a new filter.** Place it in `system/filter`. Filters are the one place where imports from both `adapter` and `core` are acceptable. Set the priority relative to the existing chain: `RequestValidationFilter` and `AuthRateLimitFilter` run first, then `RateLimitFilter`, then `AccessControlFilter`, then authentication.

**Finding where something is implemented.** Start at the interface in `core/port/out`. Search for classes that `implements` it. In-memory implementations are in `adapter/out/storage/memory` or `adapter/out/ratelimit/memory`. Redis implementations are in the corresponding `redis` subpackages. Cassandra implementations are in `adapter/out/storage/cassandra`.
