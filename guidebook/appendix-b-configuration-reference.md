# Appendix B: Configuration Reference

This appendix documents every configuration property in the Aussie API Gateway. Each section covers a functional area: the property prefix, every available property with its default value and type, the `@ConfigMapping` interface that backs it, the corresponding environment variable override, and any profile-specific behavior.

Properties are grouped by the same functional boundaries used in the codebase. Where a property has security implications -- where setting it incorrectly creates an exploitable vulnerability or where its value must never appear in version control -- those implications are called out explicitly.

All durations use ISO-8601 format (e.g., `PT30S` for 30 seconds, `PT1H` for 1 hour, `P7D` for 7 days). Quarkus validates duration formats at startup; malformed values prevent the application from starting.

---

## 1. Gateway Core

### 1.1 Forwarding Headers

**Prefix:** `aussie.gateway.forwarding`
**Interface:** `aussie.adapter.out.http.ForwardingConfig` (nested in `GatewayConfig`)
**Source:** `api/src/main/java/aussie/adapter/out/http/ForwardingConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.gateway.forwarding.use-rfc7239` | `boolean` | `true` | `AUSSIE_GATEWAY_FORWARDING_USE_RFC7239` | When true, uses RFC 7239 `Forwarded` header. When false, uses `X-Forwarded-For`, `X-Forwarded-Host`, `X-Forwarded-Proto`. |

**Profile overrides:** None.

### 1.2 Request Size Limits

**Prefix:** `aussie.gateway.limits`
**Interface:** `aussie.core.model.common.LimitsConfig` (nested in `GatewayConfig`)
**Source:** `api/src/main/java/aussie/core/model/common/LimitsConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.gateway.limits.max-body-size` | `long` | `10485760` (10 MB) | `AUSSIE_GATEWAY_LIMITS_MAX_BODY_SIZE` | Maximum request body size in bytes. |
| `aussie.gateway.limits.max-header-size` | `int` | `8192` (8 KB) | `AUSSIE_GATEWAY_LIMITS_MAX_HEADER_SIZE` | Maximum size of a single header in bytes. |
| `aussie.gateway.limits.max-total-headers-size` | `int` | `32768` (32 KB) | `AUSSIE_GATEWAY_LIMITS_MAX_TOTAL_HEADERS_SIZE` | Maximum total size of all headers in bytes. |

**Profile overrides:** None.

### 1.3 Access Control

**Prefix:** `aussie.gateway.access-control`
**Interface:** `aussie.core.model.auth.AccessControlConfig` (nested in `GatewayConfig`)
**Source:** `api/src/main/java/aussie/core/model/auth/AccessControlConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.gateway.access-control.allowed-ips` | `Optional<List<String>>` | _(not set)_ | `AUSSIE_GATEWAY_ACCESS_CONTROL_ALLOWED_IPS` | Allowed IP addresses or CIDR ranges for private endpoint access. Example: `10.0.0.0/8,192.168.0.0/16,127.0.0.1`. |
| `aussie.gateway.access-control.allowed-domains` | `Optional<List<String>>` | _(not set)_ | `AUSSIE_GATEWAY_ACCESS_CONTROL_ALLOWED_DOMAINS` | Allowed domains for private endpoint access. |
| `aussie.gateway.access-control.allowed-subdomains` | `Optional<List<String>>` | _(not set)_ | `AUSSIE_GATEWAY_ACCESS_CONTROL_ALLOWED_SUBDOMAINS` | Wildcard subdomain patterns, e.g., `*.internal.example.com`. |

**Profile overrides:** None.

**Security considerations:** These properties control network-level access restrictions. In production, configure `allowed-ips` to restrict admin endpoint access to internal network ranges. Leaving all three unset means no IP-based restriction is enforced.

### 1.4 Trusted Proxy

**Prefix:** `aussie.gateway.trusted-proxy`
**Interface:** `aussie.core.model.common.TrustedProxyConfig` (nested in `GatewayConfig`)
**Source:** `api/src/main/java/aussie/core/model/common/TrustedProxyConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.gateway.trusted-proxy.enabled` | `boolean` | `false` | `AUSSIE_GATEWAY_TRUSTED_PROXY_ENABLED` | When true, forwarding headers (`X-Forwarded-For`, `Forwarded`, `X-Real-IP`) are only trusted from connections originating from listed proxy IPs/CIDRs. |
| `aussie.gateway.trusted-proxy.proxies` | `Optional<List<String>>` | _(not set)_ | `AUSSIE_GATEWAY_TRUSTED_PROXY_PROXIES` | Trusted proxy IPs and CIDR ranges (comma-separated). Example: `10.0.0.0/8,192.168.0.0/16`. |

**Profile overrides:** None.

**Security considerations:** If Aussie is deployed behind a load balancer or reverse proxy, enable this and configure the proxy addresses. Without trusted proxy validation, any client can forge forwarding headers to spoof their IP address, bypassing IP-based access controls and rate limiting.

### 1.5 Gateway Security

**Prefix:** `aussie.gateway.security`
**Interface:** `aussie.adapter.out.http.SecurityConfig` (nested in `GatewayConfig`, extends `GatewaySecurityConfig`)
**Source:** `api/src/main/java/aussie/adapter/out/http/SecurityConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.gateway.security.public-default-visibility-enabled` | `boolean` | `false` | `AUSSIE_GATEWAY_SECURITY_PUBLIC_DEFAULT_VISIBILITY_ENABLED` | When false, services cannot set `defaultVisibility` to `PUBLIC`. When true, services may expose endpoints publicly by default. |

**Profile overrides:** None.

**Security considerations:** Enabling this allows service teams to register endpoints with public visibility by default. Keep disabled unless you have a specific need for publicly accessible service endpoints without per-endpoint visibility configuration.

---

## 2. CORS

**Prefix:** `aussie.gateway.cors`
**Interface:** `aussie.adapter.in.http.GatewayCorsConfig`
**Source:** `api/src/main/java/aussie/adapter/in/http/GatewayCorsConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.gateway.cors.enabled` | `boolean` | `true` | `AUSSIE_GATEWAY_CORS_ENABLED` | Enable CORS handling. |
| `aussie.gateway.cors.allowed-origins` | `List<String>` | `*` | `AUSSIE_GATEWAY_CORS_ALLOWED_ORIGINS` | Allowed origins. Use `*` for any. Supports wildcard subdomains like `*.example.com`. |
| `aussie.gateway.cors.allowed-methods` | `Set<String>` | `GET,POST,PUT,DELETE,PATCH,OPTIONS,HEAD` | `AUSSIE_GATEWAY_CORS_ALLOWED_METHODS` | Allowed HTTP methods. |
| `aussie.gateway.cors.allowed-headers` | `Set<String>` | `Content-Type,Authorization,X-Requested-With,Accept,Origin` | `AUSSIE_GATEWAY_CORS_ALLOWED_HEADERS` | Allowed request headers. |
| `aussie.gateway.cors.exposed-headers` | `Optional<Set<String>>` | _(not set)_ | `AUSSIE_GATEWAY_CORS_EXPOSED_HEADERS` | Headers exposed to the browser. |
| `aussie.gateway.cors.allow-credentials` | `boolean` | `true` | `AUSSIE_GATEWAY_CORS_ALLOW_CREDENTIALS` | Allow credentials (cookies, authorization headers). |
| `aussie.gateway.cors.max-age` | `Optional<Long>` | `3600` | `AUSSIE_GATEWAY_CORS_MAX_AGE` | Max age for preflight cache in seconds. |

**Profile overrides:**

| Profile | Property | Value |
|---------|----------|-------|
| `%dev` | `aussie.gateway.cors.allowed-origins` | `http://localhost:3000,http://127.0.0.1:3000` |
| `%prod` | `aussie.gateway.cors.enabled` | `true` |

**Security considerations:** The default `allowed-origins=*` is permissive. When `allow-credentials=true`, the combination with `allowed-origins=*` is insecure -- browsers will reject this combination, but the configuration should be tightened in production to list specific allowed origins. The dev profile narrows origins to `localhost:3000`.

---

## 3. Security Headers

**Prefix:** `aussie.gateway.security-headers`
**Interface:** `aussie.adapter.in.http.SecurityHeadersConfig`
**Source:** `api/src/main/java/aussie/adapter/in/http/SecurityHeadersConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.gateway.security-headers.enabled` | `boolean` | `true` | `AUSSIE_GATEWAY_SECURITY_HEADERS_ENABLED` | Enable security headers on all responses. |
| `aussie.gateway.security-headers.content-type-options` | `String` | `nosniff` | `AUSSIE_GATEWAY_SECURITY_HEADERS_CONTENT_TYPE_OPTIONS` | `X-Content-Type-Options` header. Prevents MIME type sniffing. |
| `aussie.gateway.security-headers.frame-options` | `String` | `DENY` | `AUSSIE_GATEWAY_SECURITY_HEADERS_FRAME_OPTIONS` | `X-Frame-Options` header. Controls iframe embedding. |
| `aussie.gateway.security-headers.content-security-policy` | `String` | `default-src 'none'` | `AUSSIE_GATEWAY_SECURITY_HEADERS_CONTENT_SECURITY_POLICY` | `Content-Security-Policy` header. |
| `aussie.gateway.security-headers.referrer-policy` | `String` | `strict-origin-when-cross-origin` | `AUSSIE_GATEWAY_SECURITY_HEADERS_REFERRER_POLICY` | `Referrer-Policy` header. |
| `aussie.gateway.security-headers.permitted-cross-domain-policies` | `String` | `none` | `AUSSIE_GATEWAY_SECURITY_HEADERS_PERMITTED_CROSS_DOMAIN_POLICIES` | `X-Permitted-Cross-Domain-Policies` header. |
| `aussie.gateway.security-headers.strict-transport-security` | `Optional<String>` | _(not set)_ | `AUSSIE_GATEWAY_SECURITY_HEADERS_STRICT_TRANSPORT_SECURITY` | `Strict-Transport-Security` header. Only enable when behind TLS termination. Example: `max-age=31536000; includeSubDomains`. |
| `aussie.gateway.security-headers.permissions-policy` | `Optional<String>` | _(not set)_ | `AUSSIE_GATEWAY_SECURITY_HEADERS_PERMISSIONS_POLICY` | `Permissions-Policy` header. Controls browser features. Example: `camera=(), microphone=(), geolocation=()`. |

**Profile overrides:** None defined. The production profile comments suggest enabling `strict-transport-security` when behind TLS.

**Security considerations:** HSTS (`strict-transport-security`) must only be enabled when TLS termination is confirmed. Incorrect HSTS configuration can lock browsers out of your site for the duration of `max-age`. The defaults follow OWASP recommendations.

---

## 4. Authentication

### 4.1 Core Auth Properties

**Prefix:** `aussie.auth`
**Interface:** None (`@ConfigProperty` reads via `ConfigProvider.getConfig()`)
**Source:** `api/src/main/java/aussie/system/filter/AuthenticationFilter.java`, `api/src/main/java/aussie/adapter/in/auth/NoopAuthProvider.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.enabled` | `boolean` | `true` | `AUSSIE_AUTH_ENABLED` | Enable/disable authentication globally. |
| `aussie.auth.admin-paths-only` | `boolean` | `true` | `AUSSIE_AUTH_ADMIN_PATHS_ONLY` | When true, only `/admin/*` paths require authentication. Gateway and pass-through routes remain open. |
| `aussie.auth.dangerous-noop` | `boolean` | `false` | `AUSSIE_AUTH_DANGEROUS_NOOP` | Disable authentication entirely. **Application refuses to start if enabled in production mode** (`LaunchMode.NORMAL`). |

**Profile overrides:** None in properties. The `NoopAuthGuard` enforces that `dangerous-noop=true` is rejected in production.

**Security considerations:** `dangerous-noop` is the most security-critical flag in the entire configuration. The name is deliberately alarming. Setting it to `true` disables all authentication for every service behind the gateway. A startup guard (`NoopAuthGuard`) throws `IllegalStateException` if this is enabled in production, preventing the application from serving any traffic. Never set this in production configuration or environment variables.

### 4.2 JWT Authentication

**Prefix:** `aussie.auth.jwt`
**Interface:** None (read via `@ConfigProperty`)

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.jwt.issuer` | `String` | _(not set)_ | `AUSSIE_AUTH_JWT_ISSUER` | Expected JWT issuer (e.g., `https://auth.example.com`). |
| `aussie.auth.jwt.audience` | `String` | _(not set)_ | `AUSSIE_AUTH_JWT_AUDIENCE` | Expected JWT audience (e.g., `aussie-api`). |
| `aussie.auth.jwt.jwks-uri` | `String` | _(not set)_ | `AUSSIE_AUTH_JWT_JWKS_URI` | JWKS endpoint for JWT signature verification. |

**Profile overrides:** None.

**Security considerations:** The `jwks-uri` must point to the identity provider's actual JWKS endpoint. A misconfigured URI could cause all JWT validation to fail (if unreachable) or accept tokens from the wrong issuer (if pointed at an attacker-controlled endpoint).

### 4.3 Auth Key Encryption

**Prefix:** `aussie.auth.encryption`
**Interface:** None (read via `@ConfigProperty` in `ApiKeyEncryptionService`)
**Source:** `api/src/main/java/aussie/core/service/auth/ApiKeyEncryptionService.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.encryption.key` | `Optional<String>` | _(not set)_ | `AUTH_ENCRYPTION_KEY` | AES-256 encryption key for API key records at rest. Base64-encoded 256-bit key. Generate with: `openssl rand -base64 32`. |
| `aussie.auth.encryption.key-id` | `String` | `v1` | `AUSSIE_AUTH_ENCRYPTION_KEY_ID` | Key ID for encryption key rotation tracking. |

**Profile overrides:** None.

**Security considerations:** This value **must never appear in version control**. Store it in a secrets manager or inject via environment variable. When not set, API key records are stored in plaintext with a `PLAIN:` prefix marker. The service logs a warning but does not fail. The key must be exactly 32 bytes (256 bits) after Base64 decoding; the application validates this at startup and refuses to start with an incorrectly sized key.

### 4.4 API Key Configuration

**Prefix:** `aussie.auth.api-keys`
**Interface:** `aussie.core.config.ApiKeyConfig`
**Source:** `api/src/main/java/aussie/core/config/ApiKeyConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.api-keys.max-ttl` | `Optional<Duration>` | _(not set)_ | `AUSSIE_AUTH_API_KEYS_MAX_TTL` | Maximum TTL for API keys. Creation fails if requested TTL exceeds this. Examples: `P90D`, `P365D`. |

**Profile overrides:** None.

### 4.5 Auth Key Storage

**Prefix:** `aussie.auth.storage`
**Interface:** None (read via `@ConfigProperty` and SPI)

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.storage.provider` | `String` | _(auto-selects by priority)_ | `AUSSIE_AUTH_STORAGE_PROVIDER` | Storage provider for API keys. Options: `memory`, `cassandra`. |
| `aussie.auth.storage.cassandra.contact-points` | `String` | Falls back to `aussie.storage.cassandra.*` | `AUSSIE_AUTH_STORAGE_CASSANDRA_CONTACT_POINTS` | Cassandra contact points for auth storage. |
| `aussie.auth.storage.cassandra.datacenter` | `String` | `datacenter1` | `AUSSIE_AUTH_STORAGE_CASSANDRA_DATACENTER` | Local datacenter name. |
| `aussie.auth.storage.cassandra.keyspace` | `String` | `aussie` | `AUSSIE_AUTH_STORAGE_CASSANDRA_KEYSPACE` | Keyspace name. |

**Profile overrides:** None. Falls back to `aussie.storage.cassandra.*` values if not explicitly set.

### 4.6 Auth Key Cache

**Prefix:** `aussie.auth.cache`
**Interface:** None (read via `@ConfigProperty` and SPI)

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.cache.enabled` | `boolean` | `false` | `AUSSIE_AUTH_CACHE_ENABLED` | Enable caching for API key validation. Requires Redis. |
| `aussie.auth.cache.provider` | `String` | _(not set)_ | `AUSSIE_AUTH_CACHE_PROVIDER` | Cache provider. Options: `redis`. |
| `aussie.auth.cache.ttl` | `Duration` | `PT5M` | `AUSSIE_AUTH_CACHE_TTL` | Cache TTL for API key lookups. |

**Profile overrides:** None.

---

## 5. PKCE (Proof Key for Code Exchange)

**Prefix:** `aussie.auth.pkce`
**Interface:** `aussie.core.config.PkceConfig`
**Source:** `api/src/main/java/aussie/core/config/PkceConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.pkce.enabled` | `boolean` | `true` | `AUSSIE_AUTH_PKCE_ENABLED` | Enable PKCE support. |
| `aussie.auth.pkce.required` | `boolean` | `true` | `AUSSIE_AUTH_PKCE_REQUIRED` | Require PKCE for all authorization requests. When false, PKCE is optional. |
| `aussie.auth.pkce.challenge-ttl` | `Duration` | `PT10M` | `AUSSIE_AUTH_PKCE_CHALLENGE_TTL` | How long a PKCE challenge remains valid. |
| `aussie.auth.pkce.storage.provider` | `String` | `redis` | `AUSSIE_AUTH_PKCE_STORAGE_PROVIDER` | Storage provider for PKCE challenges. Options: `redis`, `memory`, or custom SPI name. |
| `aussie.auth.pkce.storage.redis.key-prefix` | `String` | `aussie:pkce:` | `AUSSIE_AUTH_PKCE_STORAGE_REDIS_KEY_PREFIX` | Redis key prefix for PKCE challenges. |

**Profile overrides:**

| Profile | Property | Value |
|---------|----------|-------|
| `%dev` | `aussie.auth.pkce.storage.provider` | `memory` |

---

## 6. OIDC Token Exchange

**Prefix:** `aussie.auth.oidc`
**Interface:** `aussie.core.config.OidcConfig`
**Source:** `api/src/main/java/aussie/core/config/OidcConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.oidc.token-exchange.enabled` | `boolean` | `false` | `AUSSIE_AUTH_OIDC_TOKEN_EXCHANGE_ENABLED` | Enable OIDC token exchange. When disabled, the `/auth/oidc/token` endpoint returns a feature-disabled error. |
| `aussie.auth.oidc.token-exchange.create-session` | `boolean` | `true` | `AUSSIE_AUTH_OIDC_TOKEN_EXCHANGE_CREATE_SESSION` | Create Aussie session after successful token exchange. |
| `aussie.auth.oidc.token-exchange.provider` | `String` | `default` | `AUSSIE_AUTH_OIDC_TOKEN_EXCHANGE_PROVIDER` | Token exchange provider. Options: `default`, or custom SPI name. |
| `aussie.auth.oidc.token-exchange.token-endpoint` | `Optional<String>` | _(not set)_ | `OIDC_TOKEN_ENDPOINT` | IdP token endpoint URL. |
| `aussie.auth.oidc.token-exchange.client-id` | `Optional<String>` | _(not set)_ | `OIDC_CLIENT_ID` | OAuth2 client ID. |
| `aussie.auth.oidc.token-exchange.client-secret` | `Optional<String>` | _(not set)_ | `OIDC_CLIENT_SECRET` | OAuth2 client secret. |
| `aussie.auth.oidc.token-exchange.client-auth-method` | `String` | `client_secret_basic` | `AUSSIE_AUTH_OIDC_TOKEN_EXCHANGE_CLIENT_AUTH_METHOD` | Client authentication method. Options: `client_secret_basic`, `client_secret_post`. |
| `aussie.auth.oidc.token-exchange.scopes` | `Set<String>` | `openid,profile,email` | `AUSSIE_AUTH_OIDC_TOKEN_EXCHANGE_SCOPES` | Scopes to request. |
| `aussie.auth.oidc.token-exchange.timeout` | `Duration` | `PT10S` | `AUSSIE_AUTH_OIDC_TOKEN_EXCHANGE_TIMEOUT` | HTTP timeout for token exchange requests. |
| `aussie.auth.oidc.token-exchange.refresh-token.store` | `boolean` | `true` | `AUSSIE_AUTH_OIDC_TOKEN_EXCHANGE_REFRESH_TOKEN_STORE` | Store refresh tokens for automatic renewal. |
| `aussie.auth.oidc.token-exchange.refresh-token.default-ttl` | `Duration` | `PT168H` (7 days) | `AUSSIE_AUTH_OIDC_TOKEN_EXCHANGE_REFRESH_TOKEN_DEFAULT_TTL` | Default TTL for stored refresh tokens. |
| `aussie.auth.oidc.token-exchange.refresh-token.key-prefix` | `String` | `aussie:oidc:refresh:` | `AUSSIE_AUTH_OIDC_TOKEN_EXCHANGE_REFRESH_TOKEN_KEY_PREFIX` | Redis key prefix for refresh tokens. |

**Profile overrides:**

| Profile | Property | Value |
|---------|----------|-------|
| `%dev` | `aussie.auth.oidc.token-exchange.enabled` | `true` |
| `%dev` | `aussie.auth.oidc.token-exchange.token-endpoint` | `http://localhost:3000/api/auth/oidc/token` |
| `%dev` | `aussie.auth.oidc.token-exchange.client-id` | `aussie-gateway` |
| `%dev` | `aussie.auth.oidc.token-exchange.client-secret` | `demo-secret` |
| `%dev` | `aussie.auth.oidc.token-exchange.create-session` | `false` |

**Security considerations:** `client-secret` **must never appear in version control**. Inject via the `OIDC_CLIENT_SECRET` environment variable from a secrets manager. The dev profile uses a hardcoded `demo-secret` which is acceptable only for local development against a demo IdP.

---

## 7. Route Authentication

**Prefix:** `aussie.auth.route-auth`
**Interface:** `aussie.core.config.RouteAuthConfig`
**Source:** `api/src/main/java/aussie/core/config/RouteAuthConfig.java`

### 7.1 Core Route Auth

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.route-auth.enabled` | `boolean` | `false` | `AUSSIE_AUTH_ROUTE_AUTH_ENABLED` | Enable per-route authentication. |

### 7.2 Token Providers (Map-based)

**Prefix:** `aussie.auth.route-auth.providers.<name>`

Token providers are configured as a named map. Each provider entry supports:

| Property | Type | Default | Env Variable Pattern | Description |
|----------|------|---------|---------------------|-------------|
| `.issuer` | `String` | _(required)_ | `AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_<NAME>_ISSUER` | Expected issuer claim (`iss`). |
| `.jwks-uri` | `String` | _(required)_ | `AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_<NAME>_JWKS_URI` | JWKS endpoint URI for key retrieval. |
| `.discovery-uri` | `Optional<String>` | _(not set)_ | `AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_<NAME>_DISCOVERY_URI` | Optional OIDC discovery endpoint. |
| `.audiences` | `Set<String>` | _(empty)_ | `AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_<NAME>_AUDIENCES` | Allowed audience claim values. |
| `.key-refresh-interval` | `Duration` | `PT1H` | `AUSSIE_AUTH_ROUTE_AUTH_PROVIDERS_<NAME>_KEY_REFRESH_INTERVAL` | JWKS key refresh interval. |
| `.claims-mapping` | `Map<String, String>` | _(empty)_ | _(complex)_ | Claims mapping from external to internal names. |

### 7.3 JWS Token Issuance

**Prefix:** `aussie.auth.route-auth.jws`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.route-auth.jws.issuer` | `String` | `aussie-gateway` | `AUSSIE_AUTH_ROUTE_AUTH_JWS_ISSUER` | Issuer claim for outbound JWS tokens. |
| `aussie.auth.route-auth.jws.key-id` | `String` | `v1` | `AUSSIE_AUTH_ROUTE_AUTH_JWS_KEY_ID` | Current signing key ID. |
| `aussie.auth.route-auth.jws.token-ttl` | `Duration` | `PT5M` | `AUSSIE_AUTH_ROUTE_AUTH_JWS_TOKEN_TTL` | Default TTL for issued JWS tokens. |
| `aussie.auth.route-auth.jws.max-token-ttl` | `Duration` | `PT24H` | `AUSSIE_AUTH_ROUTE_AUTH_JWS_MAX_TOKEN_TTL` | Maximum allowed TTL. Tokens with longer IdP expiry are clamped. |
| `aussie.auth.route-auth.jws.forwarded-claims` | `Set<String>` | `sub,email,name,groups,roles,effective_permissions` | `AUSSIE_AUTH_ROUTE_AUTH_JWS_FORWARDED_CLAIMS` | Claims to forward from original token to downstream services. |
| `aussie.auth.route-auth.jws.signing-key` | `Optional<String>` | _(not set)_ | `AUSSIE_JWS_SIGNING_KEY` | RSA private key for signing (PKCS#8 PEM format). Required when route-auth is enabled. |
| `aussie.auth.route-auth.jws.default-audience` | `Optional<String>` | _(not set)_ | `AUSSIE_AUTH_ROUTE_AUTH_JWS_DEFAULT_AUDIENCE` | Default audience claim for issued tokens. |
| `aussie.auth.route-auth.jws.require-audience` | `boolean` | `false` | `AUSSIE_AUTH_ROUTE_AUTH_JWS_REQUIRE_AUDIENCE` | Require audience claim in all issued tokens. |

**Profile overrides:**

| Profile | Property | Value |
|---------|----------|-------|
| `%dev` | `aussie.auth.route-auth.enabled` | `true` |
| `%dev` | `aussie.auth.route-auth.providers.demo.issuer` | `demo-app` |
| `%dev` | `aussie.auth.route-auth.providers.demo.jwks-uri` | `${DEMO_APP_URL:http://localhost:3000}/.well-known/jwks.json` |
| `%dev` | `aussie.auth.route-auth.providers.demo.audiences` | `aussie-gateway` |
| `%dev` | `aussie.auth.route-auth.jws.signing-key` | `${AUSSIE_JWS_SIGNING_KEY}` |

**Security considerations:** `signing-key` **must never appear in version control**. This is an RSA private key in PKCS#8 PEM format. Generate with `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`. Store in a secrets manager and inject via `AUSSIE_JWS_SIGNING_KEY`. Compromise of this key allows forging authentication tokens for all downstream services.

---

## 8. Signing Key Rotation

**Prefix:** `aussie.auth.key-rotation`
**Interface:** `aussie.core.config.KeyRotationConfig`
**Source:** `api/src/main/java/aussie/core/config/KeyRotationConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.key-rotation.enabled` | `boolean` | `false` | `AUSSIE_AUTH_KEY_ROTATION_ENABLED` | Enable automated key rotation. When disabled, uses static key from `route-auth.jws.signing-key`. |
| `aussie.auth.key-rotation.schedule` | `String` | `0 0 0 1 */3 ?` | `AUSSIE_AUTH_KEY_ROTATION_SCHEDULE` | Quartz cron schedule for rotation. Default: quarterly (first day of Jan, Apr, Jul, Oct at midnight UTC). |
| `aussie.auth.key-rotation.cache-refresh-interval` | `Duration` | `PT5M` | `AUSSIE_AUTH_KEY_ROTATION_CACHE_REFRESH_INTERVAL` | How often to refresh in-memory key cache from repository. Lower values = faster propagation, higher storage load. |
| `aussie.auth.key-rotation.cleanup-interval` | `Duration` | `PT1H` | `AUSSIE_AUTH_KEY_ROTATION_CLEANUP_INTERVAL` | How often to run the retired key cleanup job. |
| `aussie.auth.key-rotation.grace-period` | `Duration` | `PT24H` | `AUSSIE_AUTH_KEY_ROTATION_GRACE_PERIOD` | Grace period before new key becomes active (PENDING status). Allows propagation to all instances. |
| `aussie.auth.key-rotation.deprecation-period` | `Duration` | `P7D` | `AUSSIE_AUTH_KEY_ROTATION_DEPRECATION_PERIOD` | How long deprecated keys remain valid for verification. Tokens signed with old keys still validate. |
| `aussie.auth.key-rotation.retention-period` | `Duration` | `P30D` | `AUSSIE_AUTH_KEY_ROTATION_RETENTION_PERIOD` | How long retired keys are retained before permanent deletion. |
| `aussie.auth.key-rotation.key-size` | `int` | `2048` | `AUSSIE_AUTH_KEY_ROTATION_KEY_SIZE` | RSA key size in bits. Options: `2048`, `4096`. Higher = more security, slower signing/verification. |
| `aussie.auth.key-rotation.storage` | `String` | `config` | `AUSSIE_AUTH_KEY_ROTATION_STORAGE` | Key storage backend. Options: `config`, `vault`, `database`. |
| `aussie.auth.key-rotation.vault.path` | `String` | `secret/aussie/signing-keys` | `AUSSIE_AUTH_KEY_ROTATION_VAULT_PATH` | Vault path for signing keys (when `storage=vault`). |
| `aussie.auth.key-rotation.vault.namespace` | `Optional<String>` | _(not set)_ | `AUSSIE_AUTH_KEY_ROTATION_VAULT_NAMESPACE` | Vault Enterprise namespace (when `storage=vault`). |

**Profile overrides:**

| Profile | Property | Value |
|---------|----------|-------|
| `%dev` | `aussie.auth.key-rotation.enabled` | `false` |

**Security considerations:** When using Vault storage, the Vault path and namespace are sensitive configuration that reveals your secrets infrastructure topology. The `deprecation-period` and `retention-period` have security implications: too short and valid tokens will be rejected prematurely; too long and compromised keys remain trusted. The defaults (7 days deprecation, 30 days retention) provide a conservative balance.

---

## 9. Token Revocation

**Prefix:** `aussie.auth.revocation`
**Interface:** `aussie.core.config.TokenRevocationConfig`
**Source:** `api/src/main/java/aussie/core/config/TokenRevocationConfig.java`

### 9.1 Core Revocation

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.revocation.enabled` | `boolean` | `true` | `AUSSIE_AUTH_REVOCATION_ENABLED` | Enable token revocation checks. |
| `aussie.auth.revocation.check-user-revocation` | `boolean` | `true` | `AUSSIE_AUTH_REVOCATION_CHECK_USER_REVOCATION` | Enable user-level revocation (logout everywhere). |
| `aussie.auth.revocation.check-threshold` | `Duration` | `PT30S` | `AUSSIE_AUTH_REVOCATION_CHECK_THRESHOLD` | Skip revocation check for tokens expiring within this threshold. Set to `PT0S` to always check (not recommended for high traffic). |

### 9.2 Bloom Filter

**Prefix:** `aussie.auth.revocation.bloom-filter`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.revocation.bloom-filter.enabled` | `boolean` | `true` | `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_ENABLED` | Enable bloom filter optimization for O(1) negative lookups. |
| `aussie.auth.revocation.bloom-filter.expected-insertions` | `int` | `100000` | `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_EXPECTED_INSERTIONS` | Expected number of revoked tokens. Sizing guide: <1K RPS = 100,000 (~1.2 MB); 1-10K RPS = 1,000,000 (~12 MB); >10K RPS = 10,000,000 (~120 MB). |
| `aussie.auth.revocation.bloom-filter.false-positive-probability` | `double` | `0.001` | `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_FALSE_POSITIVE_PROBABILITY` | Desired false positive rate. 0.001 = 0.1% (~10 bits/element). 0.01 = 1% (~7 bits/element, 30% less memory). 0.0001 = 0.01% (~13 bits/element, 30% more memory). |
| `aussie.auth.revocation.bloom-filter.rebuild-interval` | `Duration` | `PT1H` | `AUSSIE_AUTH_REVOCATION_BLOOM_FILTER_REBUILD_INTERVAL` | Full bloom filter rebuild interval from remote store. |

### 9.3 Local Cache

**Prefix:** `aussie.auth.revocation.cache`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.revocation.cache.enabled` | `boolean` | `true` | `AUSSIE_AUTH_REVOCATION_CACHE_ENABLED` | Enable local LRU cache for confirmed revocations. |
| `aussie.auth.revocation.cache.max-size` | `int` | `10000` | `AUSSIE_AUTH_REVOCATION_CACHE_MAX_SIZE` | Maximum cache entries. |
| `aussie.auth.revocation.cache.ttl` | `Duration` | `PT5M` | `AUSSIE_AUTH_REVOCATION_CACHE_TTL` | Cache entry TTL. |

### 9.4 Pub/Sub

**Prefix:** `aussie.auth.revocation.pubsub`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.revocation.pubsub.enabled` | `boolean` | `true` | `AUSSIE_AUTH_REVOCATION_PUBSUB_ENABLED` | Enable pub/sub for multi-instance bloom filter synchronization. |
| `aussie.auth.revocation.pubsub.channel` | `String` | `aussie:revocation:events` | `AUSSIE_AUTH_REVOCATION_PUBSUB_CHANNEL` | Redis pub/sub channel name. |

**Profile overrides:** None.

**Security considerations:** Token revocation is fail-closed -- when Redis times out during a revocation check, the request is denied. Disabling revocation (`enabled=false`) means compromised or stolen tokens cannot be invalidated before their natural expiry. The `check-threshold` optimization trades a small security window (tokens within 30 seconds of expiry skip revocation checks) for reduced load on the revocation infrastructure.

---

## 10. Authentication Rate Limiting

**Prefix:** `aussie.auth.rate-limit`
**Interface:** `aussie.core.config.AuthRateLimitConfig`
**Source:** `api/src/main/java/aussie/core/config/AuthRateLimitConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.rate-limit.enabled` | `boolean` | `true` | `AUSSIE_AUTH_RATE_LIMIT_ENABLED` | Enable brute force protection on authentication endpoints. |
| `aussie.auth.rate-limit.max-failed-attempts` | `int` | `5` | `AUSSIE_AUTH_RATE_LIMIT_MAX_FAILED_ATTEMPTS` | Maximum failed attempts before lockout. Applies per tracking key. |
| `aussie.auth.rate-limit.lockout-duration` | `Duration` | `PT15M` | `AUSSIE_AUTH_RATE_LIMIT_LOCKOUT_DURATION` | Initial lockout duration after max failed attempts. |
| `aussie.auth.rate-limit.failed-attempt-window` | `Duration` | `PT1H` | `AUSSIE_AUTH_RATE_LIMIT_FAILED_ATTEMPT_WINDOW` | Window for tracking failed attempts. Attempts older than this are forgotten. |
| `aussie.auth.rate-limit.track-by-ip` | `boolean` | `true` | `AUSSIE_AUTH_RATE_LIMIT_TRACK_BY_IP` | Track failed attempts by client IP. Protects against single-source attacks. |
| `aussie.auth.rate-limit.track-by-identifier` | `boolean` | `true` | `AUSSIE_AUTH_RATE_LIMIT_TRACK_BY_IDENTIFIER` | Track failed attempts by identifier (username, email, API key prefix). Protects against distributed attacks. |
| `aussie.auth.rate-limit.progressive-lockout-multiplier` | `double` | `1.5` | `AUSSIE_AUTH_RATE_LIMIT_PROGRESSIVE_LOCKOUT_MULTIPLIER` | Multiplier for each subsequent lockout. Set to `1.0` to disable progressive lockout. |
| `aussie.auth.rate-limit.max-lockout-duration` | `Duration` | `PT24H` | `AUSSIE_AUTH_RATE_LIMIT_MAX_LOCKOUT_DURATION` | Maximum lockout duration cap for progressive lockout. |
| `aussie.auth.rate-limit.include-headers` | `boolean` | `true` | `AUSSIE_AUTH_RATE_LIMIT_INCLUDE_HEADERS` | Include rate limit headers in 401/429 authentication error responses. |

**Profile overrides:**

| Profile | Property | Value |
|---------|----------|-------|
| `%test` | `aussie.auth.rate-limit.enabled` | `false` |

The test profile disables auth rate limiting because test suites exercising authentication would trip brute force protection after five failures, causing cascading test failures unrelated to the code under test.

**Security considerations:** Disabling `include-headers` in production may be desirable to avoid leaking rate limit state to attackers. The `progressive-lockout-multiplier` at `1.5` means: first lockout = 15 minutes, second = 22.5 minutes, third = 33.75 minutes, up to the 24-hour cap.

---

## 11. Token Translation

**Prefix:** `aussie.auth.token-translation`
**Interface:** `aussie.core.config.TokenTranslationConfig`
**Source:** `api/src/main/java/aussie/core/config/TokenTranslationConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.auth.token-translation.enabled` | `boolean` | `false` | `AUSSIE_AUTH_TOKEN_TRANSLATION_ENABLED` | Enable token translation for custom IdP claim structures. |
| `aussie.auth.token-translation.provider` | `String` | `default` | `AUSSIE_AUTH_TOKEN_TRANSLATION_PROVIDER` | Provider name. Options: `default`, `config`, `remote`, or custom SPI name. |
| `aussie.auth.token-translation.config.path` | `Optional<String>` | _(not set)_ | `AUSSIE_AUTH_TOKEN_TRANSLATION_CONFIG_PATH` | Path to translation config file (JSON/YAML) for `config` provider. |
| `aussie.auth.token-translation.remote.url` | `Optional<String>` | _(not set)_ | `AUSSIE_AUTH_TOKEN_TRANSLATION_REMOTE_URL` | URL of remote translation service for `remote` provider. |
| `aussie.auth.token-translation.remote.timeout` | `Duration` | `PT0.1S` (100ms) | `AUSSIE_AUTH_TOKEN_TRANSLATION_REMOTE_TIMEOUT` | Request timeout for remote translation service. |
| `aussie.auth.token-translation.remote.fail-mode` | `FailMode` | `deny` | `AUSSIE_AUTH_TOKEN_TRANSLATION_REMOTE_FAIL_MODE` | Behavior when remote service fails. Options: `deny` (reject access), `allow_empty` (allow with empty roles/permissions). |
| `aussie.auth.token-translation.cache.ttl-seconds` | `int` | `300` (5 min) | `AUSSIE_AUTH_TOKEN_TRANSLATION_CACHE_TTL_SECONDS` | TTL for cached translation results. |
| `aussie.auth.token-translation.cache.max-size` | `long` | `10000` | `AUSSIE_AUTH_TOKEN_TRANSLATION_CACHE_MAX_SIZE` | Maximum cached translation results. |

**Profile overrides:** None.

**Security considerations:** The `fail-mode` for the remote provider defaults to `deny`, which is the safer choice. Switching to `allow_empty` means users will be authenticated but with no roles or permissions when the translation service is unavailable.

---

## 12. Bootstrap

**Prefix:** `aussie.bootstrap`
**Interface:** `aussie.core.config.BootstrapConfig`
**Source:** `api/src/main/java/aussie/core/config/BootstrapConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.bootstrap.enabled` | `boolean` | `false` | `AUSSIE_BOOTSTRAP_ENABLED` | Enable bootstrap mode for first-time admin setup. |
| `aussie.bootstrap.key` | `Optional<String>` | _(not set)_ | `AUSSIE_BOOTSTRAP_KEY` | Bootstrap key. Must be at least 32 characters. Required when bootstrap is enabled. |
| `aussie.bootstrap.ttl` | `Duration` | `PT24H` | `AUSSIE_BOOTSTRAP_TTL` | Bootstrap key TTL. Maximum allowed is 24 hours; values exceeding this are capped. |
| `aussie.bootstrap.recovery-mode` | `boolean` | `false` | `AUSSIE_BOOTSTRAP_RECOVERY_MODE` | Allow bootstrap even when admin keys exist. For emergency recovery only. |

**Profile overrides:** None.

**Security considerations:** The bootstrap key **must never appear in version control**. Always inject via `AUSSIE_BOOTSTRAP_KEY`. The key is hashed before storage; the plaintext is never logged or persisted. The 24-hour TTL cap ensures bootstrap keys are short-lived, forcing operators to create properly managed keys. `recovery-mode` should only be enabled during emergency recovery when all admin keys are lost; it allows creating a new admin key even when existing keys are present.

---

## 13. Sessions

**Prefix:** `aussie.session`
**Interface:** `aussie.core.config.SessionConfig`
**Source:** `api/src/main/java/aussie/core/config/SessionConfig.java`

### 13.1 Core Session

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.session.enabled` | `boolean` | `true` | `AUSSIE_SESSION_ENABLED` | Enable session management. When disabled, uses Authorization header flow. |
| `aussie.session.ttl` | `Duration` | `PT8H` | `AUSSIE_SESSION_TTL` | Session time-to-live. |
| `aussie.session.idle-timeout` | `Duration` | `PT30M` | `AUSSIE_SESSION_IDLE_TIMEOUT` | Invalidate session after inactivity. |
| `aussie.session.sliding-expiration` | `boolean` | `true` | `AUSSIE_SESSION_SLIDING_EXPIRATION` | Refresh session TTL on each request. |
| `aussie.session.id-generation.max-retries` | `int` | `3` | `AUSSIE_SESSION_ID_GENERATION_MAX_RETRIES` | Maximum retries for session ID collision. |

### 13.2 Cookie Configuration

**Prefix:** `aussie.session.cookie`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.session.cookie.name` | `String` | `aussie_session` | `AUSSIE_SESSION_COOKIE_NAME` | Session cookie name. |
| `aussie.session.cookie.path` | `String` | `/` | `AUSSIE_SESSION_COOKIE_PATH` | Cookie path. |
| `aussie.session.cookie.domain` | `Optional<String>` | _(not set)_ | `AUSSIE_SESSION_COOKIE_DOMAIN` | Cookie domain. Defaults to request domain if not set. |
| `aussie.session.cookie.secure` | `boolean` | `true` | `AUSSIE_SESSION_COOKIE_SECURE` | Mark cookie as HTTPS-only. |
| `aussie.session.cookie.http-only` | `boolean` | `true` | `AUSSIE_SESSION_COOKIE_HTTP_ONLY` | Mark cookie as HttpOnly (not accessible via JavaScript). |
| `aussie.session.cookie.same-site` | `String` | `Lax` | `AUSSIE_SESSION_COOKIE_SAME_SITE` | SameSite attribute. Options: `Strict`, `Lax`, `None`. |

### 13.3 Session Storage

**Prefix:** `aussie.session.storage`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.session.storage.provider` | `String` | `redis` | `AUSSIE_SESSION_STORAGE_PROVIDER` | Storage provider. Options: `redis`, `memory`, or custom SPI name. |
| `aussie.session.storage.redis.key-prefix` | `String` | `aussie:session:` | `AUSSIE_SESSION_STORAGE_REDIS_KEY_PREFIX` | Redis key prefix for session data. |

### 13.4 Session JWS Tokens

**Prefix:** `aussie.session.jws`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.session.jws.enabled` | `boolean` | `true` | `AUSSIE_SESSION_JWS_ENABLED` | Enable JWS token generation for downstream services. |
| `aussie.session.jws.ttl` | `Duration` | `PT5M` | `AUSSIE_SESSION_JWS_TTL` | JWS token TTL (should be short-lived). |
| `aussie.session.jws.issuer` | `String` | `aussie-gateway` | `AUSSIE_SESSION_JWS_ISSUER` | JWS issuer claim. |
| `aussie.session.jws.audience` | `Optional<String>` | _(not set)_ | `AUSSIE_SESSION_JWS_AUDIENCE` | JWS audience claim. |
| `aussie.session.jws.include-claims` | `List<String>` | `sub,email,name,roles` | `AUSSIE_SESSION_JWS_INCLUDE_CLAIMS` | Claims to include from session in JWS token. |

**Profile overrides:**

| Profile | Property | Value |
|---------|----------|-------|
| `%dev` | `aussie.session.storage.provider` | `memory` |
| `%dev` | `aussie.session.cookie.secure` | `false` |

The dev profile uses in-memory session storage (no Redis required) and disables the `Secure` cookie flag so sessions work over `http://localhost`.

**Security considerations:** In production, `cookie.secure` must be `true` and `cookie.http-only` must be `true`. Setting `same-site=None` requires `secure=true` and is only appropriate for cross-site embedding scenarios. Session JWS tokens should have a short TTL (the 5-minute default is appropriate) because they are bearer tokens sent to downstream services.

---

## 14. Rate Limiting

**Prefix:** `aussie.rate-limiting`
**Interface:** `aussie.core.config.RateLimitingConfig`
**Source:** `api/src/main/java/aussie/core/config/RateLimitingConfig.java`

### 14.1 Core Rate Limiting

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.rate-limiting.enabled` | `boolean` | `true` | `AUSSIE_RATE_LIMITING_ENABLED` | Master toggle for rate limiting. |
| `aussie.rate-limiting.algorithm` | `RateLimitAlgorithm` | `BUCKET` | `AUSSIE_RATE_LIMITING_ALGORITHM` | Algorithm. Options: `BUCKET`, `FIXED_WINDOW`, `SLIDING_WINDOW`. **Platform teams only.** |
| `aussie.rate-limiting.platform-max-requests-per-window` | `long` | `9223372036854775807` (`Long.MAX_VALUE`) | `AUSSIE_RATE_LIMITING_PLATFORM_MAX_REQUESTS_PER_WINDOW` | Platform-wide ceiling. Service/endpoint limits are capped to this value. **Platform teams only.** |
| `aussie.rate-limiting.default-requests-per-window` | `long` | `100` | `AUSSIE_RATE_LIMITING_DEFAULT_REQUESTS_PER_WINDOW` | Default requests per window for services without explicit configuration. |
| `aussie.rate-limiting.window-seconds` | `long` | `60` | `AUSSIE_RATE_LIMITING_WINDOW_SECONDS` | Default time window in seconds. |
| `aussie.rate-limiting.burst-capacity` | `long` | `100` | `AUSSIE_RATE_LIMITING_BURST_CAPACITY` | Default burst capacity for token bucket algorithm. |
| `aussie.rate-limiting.include-headers` | `boolean` | `true` | `AUSSIE_RATE_LIMITING_INCLUDE_HEADERS` | Include `X-RateLimit-*` headers in responses. |
| `aussie.rate-limiting.redis.enabled` | `boolean` | `false` | `AUSSIE_RATE_LIMITING_REDIS_ENABLED` | Enable Redis for distributed rate limiting. Falls back to in-memory when disabled or unavailable. |

### 14.2 WebSocket Rate Limiting

**Prefix:** `aussie.rate-limiting.websocket`

#### Connection Rate Limiting

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.rate-limiting.websocket.connection.enabled` | `boolean` | `true` | `AUSSIE_RATE_LIMITING_WEBSOCKET_CONNECTION_ENABLED` | Enable connection establishment rate limiting. |
| `aussie.rate-limiting.websocket.connection.requests-per-window` | `long` | `10` | `AUSSIE_RATE_LIMITING_WEBSOCKET_CONNECTION_REQUESTS_PER_WINDOW` | Max new connections per window. |
| `aussie.rate-limiting.websocket.connection.window-seconds` | `long` | `60` | `AUSSIE_RATE_LIMITING_WEBSOCKET_CONNECTION_WINDOW_SECONDS` | Time window in seconds. |
| `aussie.rate-limiting.websocket.connection.burst-capacity` | `long` | `5` | `AUSSIE_RATE_LIMITING_WEBSOCKET_CONNECTION_BURST_CAPACITY` | Burst capacity for connection attempts. |

#### Message Rate Limiting

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.rate-limiting.websocket.message.enabled` | `boolean` | `true` | `AUSSIE_RATE_LIMITING_WEBSOCKET_MESSAGE_ENABLED` | Enable per-connection message rate limiting. |
| `aussie.rate-limiting.websocket.message.requests-per-window` | `long` | `100` | `AUSSIE_RATE_LIMITING_WEBSOCKET_MESSAGE_REQUESTS_PER_WINDOW` | Max messages per window per connection. |
| `aussie.rate-limiting.websocket.message.window-seconds` | `long` | `1` | `AUSSIE_RATE_LIMITING_WEBSOCKET_MESSAGE_WINDOW_SECONDS` | Time window in seconds (typically shorter for messages). |
| `aussie.rate-limiting.websocket.message.burst-capacity` | `long` | `50` | `AUSSIE_RATE_LIMITING_WEBSOCKET_MESSAGE_BURST_CAPACITY` | Burst capacity for messages. |

**Profile overrides:**

| Profile | Property | Value |
|---------|----------|-------|
| `%dev` | `aussie.rate-limiting.default-requests-per-window` | `1000` |
| `%dev` | `aussie.rate-limiting.burst-capacity` | `500` |

**Security considerations:** The `algorithm` and `platform-max-requests-per-window` are platform-team-only settings. Service teams can configure per-service and per-endpoint limits through the admin API, but those limits are always capped at `platform-max-requests-per-window`. Disabling rate limiting (`enabled=false`) removes all traffic protection. The Redis backend is required for distributed rate limiting across multiple Aussie instances; in-memory rate limiting is per-instance only.

---

## 15. WebSocket Connections

**Prefix:** `aussie.websocket`
**Interface:** `aussie.core.config.WebSocketConfig`
**Source:** `api/src/main/java/aussie/core/config/WebSocketConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.websocket.idle-timeout` | `Duration` | `PT5M` | `AUSSIE_WEBSOCKET_IDLE_TIMEOUT` | Close connection if no messages in either direction. |
| `aussie.websocket.max-lifetime` | `Duration` | `PT24H` | `AUSSIE_WEBSOCKET_MAX_LIFETIME` | Hard limit on connection lifetime regardless of activity. |
| `aussie.websocket.max-connections` | `int` | `10000` | `AUSSIE_WEBSOCKET_MAX_CONNECTIONS` | Maximum concurrent WebSocket connections **per instance** (not cluster-wide). |
| `aussie.websocket.ping.enabled` | `boolean` | `true` | `AUSSIE_WEBSOCKET_PING_ENABLED` | Enable ping/pong heartbeats for stale connection detection. |
| `aussie.websocket.ping.interval` | `Duration` | `PT30S` | `AUSSIE_WEBSOCKET_PING_INTERVAL` | Ping frame interval. |
| `aussie.websocket.ping.timeout` | `Duration` | `PT10S` | `AUSSIE_WEBSOCKET_PING_TIMEOUT` | Close connection if pong not received within this time. |

**Profile overrides:** None.

**Note:** `max-connections` is per Aussie instance. In a 3-instance cluster with `max-connections=10000`, the cluster can handle up to 30,000 concurrent WebSocket connections.

---

## 16. Caching

### 16.1 Local In-Memory Cache

**Prefix:** `aussie.cache.local`
**Interface:** `aussie.core.cache.LocalCacheConfig`
**Source:** `api/src/main/java/aussie/core/cache/LocalCacheConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.cache.local.service-routes-ttl` | `Duration` | `PT30S` | `AUSSIE_CACHE_LOCAL_SERVICE_ROUTES_TTL` | TTL for service route cache. Lower values = faster cross-instance consistency, higher storage load. |
| `aussie.cache.local.rate-limit-config-ttl` | `Duration` | `PT30S` | `AUSSIE_CACHE_LOCAL_RATE_LIMIT_CONFIG_TTL` | TTL for rate limit configuration cache. |
| `aussie.cache.local.sampling-config-ttl` | `Duration` | `PT30S` | `AUSSIE_CACHE_LOCAL_SAMPLING_CONFIG_TTL` | TTL for sampling configuration cache. |
| `aussie.cache.local.max-entries` | `long` | `10000` | `AUSSIE_CACHE_LOCAL_MAX_ENTRIES` | Maximum entries per cache. LRU eviction when exceeded. |
| `aussie.cache.local.jitter-factor` | `double` | `0.1` | `AUSSIE_CACHE_LOCAL_JITTER_FACTOR` | TTL jitter factor (0.0 to 0.5). Each entry's TTL varies by +/-(factor * 100)%. Prevents cache refresh storms in multi-instance deployments. Set to 0 to disable (not recommended for production). |

**Profile overrides:**

| Profile | Property | Value |
|---------|----------|-------|
| `%dev` | `aussie.cache.local.service-routes-ttl` | `PT5S` |
| `%dev` | `aussie.cache.local.rate-limit-config-ttl` | `PT5S` |
| `%dev` | `aussie.cache.local.sampling-config-ttl` | `PT5S` |

### 16.2 Service Route Cache (Distributed)

**Prefix:** `aussie.storage.cache`
**Interface:** None (read via `@ConfigProperty` and SPI)

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.storage.cache.enabled` | `boolean` | `false` | `AUSSIE_STORAGE_CACHE_ENABLED` | Enable distributed caching (requires Redis). |
| `aussie.storage.cache.provider` | `String` | _(not set)_ | `AUSSIE_STORAGE_CACHE_PROVIDER` | Cache provider. Options: `redis`. |
| `aussie.storage.cache.ttl` | `Duration` | `PT15M` | `AUSSIE_STORAGE_CACHE_TTL` | Cache TTL. |

**Profile overrides (in `application-prod.properties`):**

| Profile | Property | Value |
|---------|----------|-------|
| `%prod` | `aussie.storage.cache.enabled` | `true` |
| `%prod` | `aussie.storage.cache.provider` | `redis` |
| `%prod` | `aussie.storage.cache.ttl` | `PT15M` |

### 16.3 Translation Config Cache

**Prefix:** `aussie.translation-config.cache`
**Interface:** None (read via `@ConfigProperty`)

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.translation-config.cache.enabled` | `boolean` | `true` | `AUSSIE_TRANSLATION_CONFIG_CACHE_ENABLED` | Enable distributed cache layer for translation configs. |
| `aussie.translation-config.cache.provider` | `String` | _(auto-selects)_ | `AUSSIE_TRANSLATION_CONFIG_CACHE_PROVIDER` | Cache provider. Options: `redis`. |
| `aussie.translation-config.cache.redis.ttl` | `Duration` | `PT15M` | `AUSSIE_TRANSLATION_CONFIG_CACHE_REDIS_TTL` | Redis cache TTL. |
| `aussie.translation-config.cache.memory.ttl` | `Duration` | `PT5M` | `AUSSIE_TRANSLATION_CONFIG_CACHE_MEMORY_TTL` | Local in-memory (L1) cache TTL. |
| `aussie.translation-config.cache.memory.max-size` | `long` | `100` | `AUSSIE_TRANSLATION_CONFIG_CACHE_MEMORY_MAX_SIZE` | Maximum local cache entries. |

**Profile overrides:**

| Profile | Property | Value |
|---------|----------|-------|
| `%dev` | `aussie.translation-config.cache.enabled` | `false` |

---

## 17. Resilience

**Prefix:** `aussie.resiliency`
**Interface:** `aussie.core.config.ResiliencyConfig`
**Source:** `api/src/main/java/aussie/core/config/ResiliencyConfig.java`

### 17.1 HTTP Proxy

**Prefix:** `aussie.resiliency.http`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.resiliency.http.connect-timeout` | `Duration` | `PT5S` | `AUSSIE_RESILIENCY_HTTP_CONNECT_TIMEOUT` | Max time to establish TCP connection to upstream. |
| `aussie.resiliency.http.request-timeout` | `Duration` | `PT30S` | `AUSSIE_RESILIENCY_HTTP_REQUEST_TIMEOUT` | Max time to wait for response. Returns 504 Gateway Timeout if exceeded. |
| `aussie.resiliency.http.max-connections-per-host` | `int` | `50` | `AUSSIE_RESILIENCY_HTTP_MAX_CONNECTIONS_PER_HOST` | Max connections per upstream host (bulkhead). |
| `aussie.resiliency.http.max-connections` | `int` | `200` | `AUSSIE_RESILIENCY_HTTP_MAX_CONNECTIONS` | Max total connections across all upstream hosts (bulkhead). |

### 17.2 JWKS

**Prefix:** `aussie.resiliency.jwks`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.resiliency.jwks.fetch-timeout` | `Duration` | `PT5S` | `AUSSIE_RESILIENCY_JWKS_FETCH_TIMEOUT` | Max time to fetch JWKS from identity provider. Falls back to cached keys if available. |
| `aussie.resiliency.jwks.max-cache-entries` | `int` | `100` | `AUSSIE_RESILIENCY_JWKS_MAX_CACHE_ENTRIES` | Max JWKS entries to cache (LRU eviction). |
| `aussie.resiliency.jwks.cache-ttl` | `Duration` | `PT1H` | `AUSSIE_RESILIENCY_JWKS_CACHE_TTL` | JWKS cache entry TTL. Entries refreshed on access after expiry. |
| `aussie.resiliency.jwks.max-connections` | `int` | `10` | `AUSSIE_RESILIENCY_JWKS_MAX_CONNECTIONS` | Max concurrent JWKS fetch connections (bulkhead). |

### 17.3 Cassandra

**Prefix:** `aussie.resiliency.cassandra`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.resiliency.cassandra.query-timeout` | `Duration` | `PT5S` | `AUSSIE_RESILIENCY_CASSANDRA_QUERY_TIMEOUT` | Max time for Cassandra query completion. |
| `aussie.resiliency.cassandra.pool-local-size` | `int` | `30` | `AUSSIE_RESILIENCY_CASSANDRA_POOL_LOCAL_SIZE` | Connections per node in local datacenter. |
| `aussie.resiliency.cassandra.max-requests-per-connection` | `int` | `1024` | `AUSSIE_RESILIENCY_CASSANDRA_MAX_REQUESTS_PER_CONNECTION` | Max concurrent requests per Cassandra connection. |

### 17.4 Redis

**Prefix:** `aussie.resiliency.redis`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.resiliency.redis.operation-timeout` | `Duration` | `PT1S` | `AUSSIE_RESILIENCY_REDIS_OPERATION_TIMEOUT` | Max time for Redis operations. |
| `aussie.resiliency.redis.pool-size` | `int` | `30` | `AUSSIE_RESILIENCY_REDIS_POOL_SIZE` | Max Redis connections in pool. |
| `aussie.resiliency.redis.pool-waiting` | `int` | `100` | `AUSSIE_RESILIENCY_REDIS_POOL_WAITING` | Max requests waiting when pool exhausted. |

**Profile overrides:**

| Profile | Property | Value |
|---------|----------|-------|
| `%prod` | `aussie.resiliency.cassandra.pool-local-size` | `50` |
| `%prod` | `aussie.resiliency.redis.pool-size` | `50` |
| `%prod` | `aussie.resiliency.redis.pool-waiting` | `200` |
| `%prod` | `aussie.resiliency.http.max-connections-per-host` | `100` |
| `%prod` | `aussie.resiliency.http.max-connections` | `500` |
| `%prod` | `aussie.resiliency.jwks.max-connections` | `20` |

**Timeout behavior by operation type:**

| Operation | Timeout Behavior |
|-----------|-----------------|
| HTTP Proxy | Returns 504 Gateway Timeout to client. |
| JWKS Fetch | Falls back to cached keys if available. |
| Session Operations | Propagates error (critical). |
| Cache Reads | Returns empty (treated as cache miss). |
| Rate Limiting | Fails open (allows request through). |
| Token Revocation | Fails closed (denies request for security). |

These fail-open and fail-closed semantics are security decisions. Rate limiting fails open to avoid blocking legitimate traffic during transient Redis outages. Token revocation fails closed because accepting a revoked token is a security breach; a brief service disruption is preferable.

---

## 18. Storage

### 18.1 Repository Provider

**Prefix:** `aussie.storage.repository`
**Interface:** None (SPI-based selection)

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.storage.repository.provider` | `String` | _(auto-selects highest priority)_ | `AUSSIE_STORAGE_REPOSITORY_PROVIDER` | Repository provider. Options: `memory` (non-persistent), `cassandra`. |

**Profile overrides (in `application-prod.properties`):**

| Profile | Property | Value |
|---------|----------|-------|
| `%prod` | `aussie.storage.repository.provider` | `cassandra` |

### 18.2 Cassandra

**Prefix:** `aussie.storage.cassandra`
**Interface:** None (read via SPI `ConfigAccess` in `CassandraStorageProvider`)
**Source:** `api/src/main/java/aussie/adapter/out/storage/cassandra/CassandraStorageProvider.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.storage.cassandra.contact-points` | `String` | `cassandra:9042` | `CASSANDRA_CONTACT_POINTS` | Comma-separated host:port pairs. |
| `aussie.storage.cassandra.datacenter` | `String` | `datacenter1` | `CASSANDRA_DATACENTER` | Local datacenter name. |
| `aussie.storage.cassandra.keyspace` | `String` | `aussie` | `CASSANDRA_KEYSPACE` | Keyspace name. |
| `aussie.storage.cassandra.run-migrations` | `boolean` | `false` | `CASSANDRA_RUN_MIGRATIONS` | Run CQL migrations from `db/cassandra/` on startup. |
| `aussie.storage.cassandra.username` | `String` | _(not set)_ | `CASSANDRA_USERNAME` | Cassandra username. |
| `aussie.storage.cassandra.password` | `String` | _(not set)_ | `CASSANDRA_PASSWORD` | Cassandra password. |

**Profile overrides (in `application-prod.properties`):**

| Profile | Property | Value |
|---------|----------|-------|
| `%prod` | `aussie.storage.cassandra.contact-points` | `${CASSANDRA_CONTACT_POINTS:localhost:9042}` |
| `%prod` | `aussie.storage.cassandra.datacenter` | `${CASSANDRA_DATACENTER:datacenter1}` |
| `%prod` | `aussie.storage.cassandra.keyspace` | `${CASSANDRA_KEYSPACE:aussie}` |
| `%prod` | `aussie.storage.cassandra.username` | `${CASSANDRA_USERNAME:}` |
| `%prod` | `aussie.storage.cassandra.password` | `${CASSANDRA_PASSWORD:}` |

**Security considerations:** `username` and `password` **must never appear in version control**. Inject via environment variables from a secrets manager. The production profile uses `${CASSANDRA_USERNAME:}` (empty default) so the application can start without credentials when Cassandra does not require authentication.

### 18.3 Translation Config Storage

**Prefix:** `aussie.translation-config.storage`
**Interface:** None (SPI-based selection)

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.translation-config.storage.provider` | `String` | _(auto-selects highest priority)_ | `AUSSIE_TRANSLATION_CONFIG_STORAGE_PROVIDER` | Storage provider. Options: `memory`, `cassandra`. |

**Profile overrides:**

| Profile | Property | Value |
|---------|----------|-------|
| `%dev` | `aussie.translation-config.storage.provider` | `memory` |

---

## 19. Observability

### 19.1 Telemetry Master Config

**Prefix:** `aussie.telemetry`
**Interface:** `aussie.adapter.out.telemetry.TelemetryConfig`
**Source:** `api/src/main/java/aussie/adapter/out/telemetry/TelemetryConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.telemetry.enabled` | `boolean` | `false` | `AUSSIE_TELEMETRY_ENABLED` | Master toggle. When disabled, all sub-features are disabled regardless of their individual settings. |

### 19.2 Tracing

**Prefix:** `aussie.telemetry.tracing`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.telemetry.tracing.enabled` | `boolean` | `false` | `AUSSIE_TELEMETRY_TRACING_ENABLED` | Enable distributed tracing with OpenTelemetry. Requires `telemetry.enabled=true`. |
| `aussie.telemetry.tracing.sampling-rate` | `double` | `1.0` | `AUSSIE_TELEMETRY_TRACING_SAMPLING_RATE` | Sampling rate (0.0 to 1.0). Only used when parent-based sampling is not in effect. |

### 19.3 Hierarchical Sampling

**Prefix:** `aussie.telemetry.sampling`
**Interface:** `aussie.core.config.SamplingConfig`
**Source:** `api/src/main/java/aussie/core/config/SamplingConfig.java`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.telemetry.sampling.enabled` | `boolean` | `false` | `AUSSIE_TELEMETRY_SAMPLING_ENABLED` | Enable hierarchical sampling with per-service and per-endpoint rates. |
| `aussie.telemetry.sampling.default-rate` | `double` | `1.0` | `AUSSIE_TELEMETRY_SAMPLING_DEFAULT_RATE` | Default sampling rate. 1.0 = all traced, 0.0 = none traced. |
| `aussie.telemetry.sampling.minimum-rate` | `double` | `0.0` | `AUSSIE_TELEMETRY_SAMPLING_MINIMUM_RATE` | Platform floor. Service configs cannot go below this. |
| `aussie.telemetry.sampling.maximum-rate` | `double` | `1.0` | `AUSSIE_TELEMETRY_SAMPLING_MAXIMUM_RATE` | Platform ceiling. Service configs cannot exceed this. |
| `aussie.telemetry.sampling.cache.redis-enabled` | `boolean` | `true` | `AUSSIE_TELEMETRY_SAMPLING_CACHE_REDIS_ENABLED` | Enable Redis caching for sampling configs. |
| `aussie.telemetry.sampling.cache.redis-ttl` | `Duration` | `PT5M` | `AUSSIE_TELEMETRY_SAMPLING_CACHE_REDIS_TTL` | Redis cache TTL for sampling configs. |
| `aussie.telemetry.sampling.lookup.timeout` | `Duration` | `PT5S` | `AUSSIE_TELEMETRY_SAMPLING_LOOKUP_TIMEOUT` | Timeout for synchronous sampling config lookups. |

### 19.4 Metrics

**Prefix:** `aussie.telemetry.metrics`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.telemetry.metrics.enabled` | `boolean` | `false` | `AUSSIE_TELEMETRY_METRICS_ENABLED` | Enable Micrometer metrics collection. Requires `telemetry.enabled=true`. |

### 19.5 Security Monitoring

**Prefix:** `aussie.telemetry.security`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.telemetry.security.enabled` | `boolean` | `false` | `AUSSIE_TELEMETRY_SECURITY_ENABLED` | Enable security monitoring for anomaly detection. |
| `aussie.telemetry.security.rate-limit-window` | `Duration` | `PT1M` | `AUSSIE_TELEMETRY_SECURITY_RATE_LIMIT_WINDOW` | Time window for rate limiting calculations. |
| `aussie.telemetry.security.rate-limit-threshold` | `int` | `1000` | `AUSSIE_TELEMETRY_SECURITY_RATE_LIMIT_THRESHOLD` | Request threshold within window before triggering alerts. |
| `aussie.telemetry.security.dos-detection.enabled` | `boolean` | `true` | `AUSSIE_TELEMETRY_SECURITY_DOS_DETECTION_ENABLED` | Enable DoS attack pattern detection. |
| `aussie.telemetry.security.dos-detection.spike-threshold` | `double` | `5.0` | `AUSSIE_TELEMETRY_SECURITY_DOS_DETECTION_SPIKE_THRESHOLD` | Spike multiplier. Alert when count exceeds `rate-limit-threshold * spike-threshold`. |
| `aussie.telemetry.security.dos-detection.error-rate-threshold` | `double` | `0.5` | `AUSSIE_TELEMETRY_SECURITY_DOS_DETECTION_ERROR_RATE_THRESHOLD` | Error rate (0.0 to 1.0) that triggers suspicious activity alerts. |

### 19.6 Traffic Attribution

**Prefix:** `aussie.telemetry.attribution`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.telemetry.attribution.enabled` | `boolean` | `false` | `AUSSIE_TELEMETRY_ATTRIBUTION_ENABLED` | Enable traffic attribution metrics for cost allocation. |
| `aussie.telemetry.attribution.team-header` | `String` | `X-Team-ID` | `AUSSIE_TELEMETRY_ATTRIBUTION_TEAM_HEADER` | Header name for team identification. |
| `aussie.telemetry.attribution.tenant-header` | `String` | `X-Tenant-ID` | `AUSSIE_TELEMETRY_ATTRIBUTION_TENANT_HEADER` | Header name for tenant identification. |
| `aussie.telemetry.attribution.client-app-header` | `String` | `X-Client-Application` | `AUSSIE_TELEMETRY_ATTRIBUTION_CLIENT_APP_HEADER` | Header name for client application identification. |

### 19.7 Span Attributes

**Prefix:** `aussie.telemetry.attributes`

| Property | Type | Default | Env Variable | Description |
|----------|------|---------|--------------|-------------|
| `aussie.telemetry.attributes.request-size` | `boolean` | `true` | `AUSSIE_TELEMETRY_ATTRIBUTES_REQUEST_SIZE` | Include request size in spans. |
| `aussie.telemetry.attributes.response-size` | `boolean` | `true` | `AUSSIE_TELEMETRY_ATTRIBUTES_RESPONSE_SIZE` | Include response size in spans. |
| `aussie.telemetry.attributes.upstream-host` | `boolean` | `true` | `AUSSIE_TELEMETRY_ATTRIBUTES_UPSTREAM_HOST` | Include upstream host. |
| `aussie.telemetry.attributes.upstream-port` | `boolean` | `true` | `AUSSIE_TELEMETRY_ATTRIBUTES_UPSTREAM_PORT` | Include upstream port. |
| `aussie.telemetry.attributes.upstream-uri` | `boolean` | `false` | `AUSSIE_TELEMETRY_ATTRIBUTES_UPSTREAM_URI` | Include full upstream URI with query params. **High cardinality** -- disabled by default to reduce storage costs. |
| `aussie.telemetry.attributes.upstream-latency` | `boolean` | `true` | `AUSSIE_TELEMETRY_ATTRIBUTES_UPSTREAM_LATENCY` | Include upstream latency. |
| `aussie.telemetry.attributes.rate-limited` | `boolean` | `true` | `AUSSIE_TELEMETRY_ATTRIBUTES_RATE_LIMITED` | Include rate limiting status. |
| `aussie.telemetry.attributes.rate-limit-remaining` | `boolean` | `true` | `AUSSIE_TELEMETRY_ATTRIBUTES_RATE_LIMIT_REMAINING` | Include remaining rate limit count. |
| `aussie.telemetry.attributes.rate-limit-type` | `boolean` | `true` | `AUSSIE_TELEMETRY_ATTRIBUTES_RATE_LIMIT_TYPE` | Include rate limit type. |
| `aussie.telemetry.attributes.rate-limit-retry-after` | `boolean` | `true` | `AUSSIE_TELEMETRY_ATTRIBUTES_RATE_LIMIT_RETRY_AFTER` | Include retry-after value. |
| `aussie.telemetry.attributes.auth-rate-limited` | `boolean` | `true` | `AUSSIE_TELEMETRY_ATTRIBUTES_AUTH_RATE_LIMITED` | Include auth rate limiting status. |
| `aussie.telemetry.attributes.auth-lockout-key` | `boolean` | `true` | `AUSSIE_TELEMETRY_ATTRIBUTES_AUTH_LOCKOUT_KEY` | Include auth lockout key. |
| `aussie.telemetry.attributes.auth-lockout-retry-after` | `boolean` | `true` | `AUSSIE_TELEMETRY_ATTRIBUTES_AUTH_LOCKOUT_RETRY_AFTER` | Include auth lockout retry-after value. |

**Profile overrides:**

| Profile | Property | Value |
|---------|----------|-------|
| `%dev` | `aussie.telemetry.enabled` | `true` |
| `%dev` | `aussie.telemetry.tracing.enabled` | `true` |
| `%dev` | `aussie.telemetry.metrics.enabled` | `true` |
| `%dev` | `aussie.telemetry.security.enabled` | `true` |
| `%dev` | `aussie.telemetry.attribution.enabled` | `true` |
| `%dev` | `aussie.telemetry.tracing.sampling-rate` | `1.0` |
| `%dev` | `aussie.telemetry.sampling.enabled` | `true` |
| `%dev` | `aussie.telemetry.attributes.upstream-uri` | `true` |

The dev profile enables all telemetry features including the high-cardinality `upstream-uri` attribute, which is useful for debugging but would produce excessive data volumes in production.

---

## 20. Quarkus and Third-Party Configuration

These properties configure Quarkus framework features and third-party integrations. They are not backed by Aussie `@ConfigMapping` interfaces but are critical to gateway operation.

### 20.1 Vert.x Event Loop

| Property | Default | `%dev` | Env Variable | Description |
|----------|---------|--------|--------------|-------------|
| `quarkus.vertx.warning-exception-time` | `2s` | `1s` | `QUARKUS_VERTX_WARNING_EXCEPTION_TIME` | Warn if event loop is blocked this long. |
| `quarkus.vertx.max-event-loop-execute-time` | `5s` | `2s` | `QUARKUS_VERTX_MAX_EVENT_LOOP_EXECUTE_TIME` | Max allowed event loop execution time. |

Dev profile uses tighter thresholds to catch blocking operations early. Production uses more lenient thresholds to avoid false alarms from transient GC pauses.

### 20.2 Redis

| Property | Default | `%prod` | Env Variable | Description |
|----------|---------|---------|--------------|-------------|
| `quarkus.redis.hosts` | `redis://localhost:6379` | `${REDIS_HOSTS:redis://localhost:6379}` | `REDIS_HOSTS` | Redis connection URI. |
| `quarkus.redis.max-pool-size` | `${aussie.resiliency.redis.pool-size}` | `10` | _(links to resiliency)_ | Max connections. References `aussie.resiliency.redis.pool-size` by default. |
| `quarkus.redis.max-pool-waiting` | `${aussie.resiliency.redis.pool-waiting}` | `24` | _(links to resiliency)_ | Max waiting requests. References `aussie.resiliency.redis.pool-waiting` by default. |
| `quarkus.redis.password` | _(not set)_ | `${REDIS_PASSWORD:}` | `REDIS_PASSWORD` | Redis authentication password. |
| `quarkus.redis.health.enabled` | `false` | _(not set)_ | `QUARKUS_REDIS_HEALTH_ENABLED` | Redis health check. Enable when Redis is available. |

**Security considerations:** `REDIS_PASSWORD` **must never appear in version control**.

### 20.3 OpenTelemetry

| Property | Default | Env Variable | Description |
|----------|---------|--------------|-------------|
| `quarkus.application.name` | `aussie-gateway` | `QUARKUS_APPLICATION_NAME` | Service name for telemetry. |
| `quarkus.otel.service.name` | `${quarkus.application.name}` | `QUARKUS_OTEL_SERVICE_NAME` | OTel service name. |
| `quarkus.otel.resource.attributes` | `deployment.environment=${AUSSIE_ENV:development}` | `AUSSIE_ENV` | OTel resource attributes. |
| `quarkus.otel.traces.enabled` | `${aussie.telemetry.tracing.enabled}` | _(links to telemetry)_ | Controlled by Aussie telemetry config. |
| `quarkus.otel.exporter.otlp.traces.endpoint` | `http://localhost:4317` | `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP collector endpoint. |
| `quarkus.otel.exporter.otlp.traces.protocol` | `grpc` | `QUARKUS_OTEL_EXPORTER_OTLP_TRACES_PROTOCOL` | OTLP protocol. |
| `quarkus.otel.traces.sampler` | `parentbased_traceidratio` | `QUARKUS_OTEL_TRACES_SAMPLER` | Sampling strategy. |
| `quarkus.otel.traces.sampler.arg` | `${aussie.telemetry.tracing.sampling-rate}` | _(links to telemetry)_ | Sampling ratio argument. |
| `quarkus.otel.propagators` | `tracecontext,baggage` | `QUARKUS_OTEL_PROPAGATORS` | W3C Trace Context propagation. |
| `quarkus.otel.span.attribute.count.limit` | `128` | `QUARKUS_OTEL_SPAN_ATTRIBUTE_COUNT_LIMIT` | Max span attributes. |
| `quarkus.otel.span.event.count.limit` | `128` | `QUARKUS_OTEL_SPAN_EVENT_COUNT_LIMIT` | Max span events. |

### 20.4 Micrometer

| Property | Default | Env Variable | Description |
|----------|---------|--------------|-------------|
| `quarkus.micrometer.enabled` | `${aussie.telemetry.metrics.enabled}` | _(links to telemetry)_ | Controlled by Aussie telemetry config. |
| `quarkus.micrometer.export.prometheus.enabled` | `${aussie.telemetry.metrics.enabled}` | _(links to telemetry)_ | Prometheus export. |
| `quarkus.micrometer.export.prometheus.path` | `/q/metrics` | `QUARKUS_MICROMETER_EXPORT_PROMETHEUS_PATH` | Metrics endpoint path. |
| `quarkus.micrometer.binder.http-client.enabled` | `true` | `QUARKUS_MICROMETER_BINDER_HTTP_CLIENT_ENABLED` | HTTP client metrics. |
| `quarkus.micrometer.binder.http-server.enabled` | `true` | `QUARKUS_MICROMETER_BINDER_HTTP_SERVER_ENABLED` | HTTP server metrics. |
| `quarkus.micrometer.binder.jvm` | `true` | `QUARKUS_MICROMETER_BINDER_JVM` | JVM metrics. |
| `quarkus.micrometer.binder.system` | `true` | `QUARKUS_MICROMETER_BINDER_SYSTEM` | System metrics. |
| `quarkus.micrometer.binder.vertx.enabled` | `true` | `QUARKUS_MICROMETER_BINDER_VERTX_ENABLED` | Vert.x metrics. |

### 20.5 HTTP Auth Permissions

| Property | Value | Description |
|----------|-------|-------------|
| `quarkus.http.auth.permission.admin.paths` | `/admin/*` | Admin endpoints require authentication. |
| `quarkus.http.auth.permission.admin.policy` | `authenticated` | |
| `quarkus.http.auth.permission.gateway.paths` | `/gateway/*` | Gateway routes are open. |
| `quarkus.http.auth.permission.gateway.policy` | `permit` | |
| `quarkus.http.auth.permission.health.paths` | `/q/*` | Health and dev endpoints are open. |
| `quarkus.http.auth.permission.health.policy` | `permit` | |
| `quarkus.http.auth.permission.passthrough.paths` | `/*` | Service pass-through (catch-all) is open. |
| `quarkus.http.auth.permission.passthrough.policy` | `permit` | |

### 20.6 Health

| Property | Default | Env Variable | Description |
|----------|---------|--------------|-------------|
| `quarkus.smallrye-health.root-path` | `/q/health` | `QUARKUS_SMALLRYE_HEALTH_ROOT_PATH` | Health endpoint path. |

---

## 21. Secrets Inventory

The following values must be injected at runtime from a secrets manager or secure environment variable source. None of these should ever appear in `application.properties`, `application-prod.properties`, or any file committed to version control.

| Secret | Env Variable | Format | Purpose | Rotation Cadence |
|--------|-------------|--------|---------|------------------|
| JWS Signing Key | `AUSSIE_JWS_SIGNING_KEY` | RSA PKCS#8 PEM | Signs session and route-auth JWS tokens | Quarterly (or automated via key rotation) |
| API Key Encryption Key | `AUTH_ENCRYPTION_KEY` | Base64-encoded 256-bit | Encrypts API key records at rest | Annually |
| Bootstrap Key | `AUSSIE_BOOTSTRAP_KEY` | String (min 32 chars) | First-time admin setup | Single-use |
| OIDC Client Secret | `OIDC_CLIENT_SECRET` | String | OAuth2 client authentication | Per IdP policy |
| Cassandra Username | `CASSANDRA_USERNAME` | String | Database authentication | Per org policy |
| Cassandra Password | `CASSANDRA_PASSWORD` | String | Database authentication | Per org policy |
| Redis Password | `REDIS_PASSWORD` | String | Redis authentication | Per org policy |

Generate keys:

```bash
# AES-256 encryption key for API key records
openssl rand -base64 32

# RSA signing key for JWS tokens
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048

# Bootstrap key (random 48-character string)
openssl rand -base64 36
```

---

## 22. Environment Variable Naming Convention

Quarkus maps property names to environment variables deterministically:

1. Dots (`.`) become underscores (`_`)
2. Hyphens (`-`) become underscores (`_`)
3. Everything is uppercased

The property `aussie.rate-limiting.platform-max-requests-per-window` becomes `AUSSIE_RATE_LIMITING_PLATFORM_MAX_REQUESTS_PER_WINDOW`.

Some properties use explicit environment variable references that do not follow this convention. These are called out in the tables above (e.g., `CASSANDRA_CONTACT_POINTS` instead of `AUSSIE_STORAGE_CASSANDRA_CONTACT_POINTS`, and `AUTH_ENCRYPTION_KEY` instead of `AUSSIE_AUTH_ENCRYPTION_KEY`). In these cases, the `application.properties` file uses `${ENV_VAR:default}` syntax to bind the non-standard name. Both the standard Quarkus-derived name and the explicit binding will work, but the explicit binding takes precedence.

---

## 23. Profile Summary

Quarkus profiles control which configuration values are active. Aussie uses three profiles:

| Profile | Activation | Purpose |
|---------|-----------|---------|
| `dev` | `quarkus:dev` or `QUARKUS_PROFILE=dev` | Local development. In-memory storage, no Redis/Cassandra required, telemetry enabled, permissive rate limits. |
| `test` | Automatic during `./gradlew test` | Automated testing. Auth rate limiting disabled to prevent cascading test failures. |
| `prod` | `-Dquarkus.profile=prod` or `QUARKUS_PROFILE=prod` | Production. Cassandra storage, Redis caching, higher connection pools, secrets via environment variables. |

**Key behavioral differences between dev and prod:**

| Concern | Dev | Prod |
|---------|-----|------|
| Storage backend | In-memory | Cassandra |
| Distributed cache | Disabled | Redis |
| Session storage | Memory | Redis |
| Session cookie secure | `false` | `true` |
| PKCE storage | Memory | Redis |
| OIDC token exchange | Enabled (demo IdP) | Disabled by default |
| Route auth | Enabled (demo provider) | Disabled by default |
| Rate limits | 1000 req/window, 500 burst | 100 req/window, 100 burst |
| Telemetry | All enabled | All disabled by default |
| Event loop detection | 1s warn, 2s max | 2s warn, 5s max |
| Connection pools (HTTP) | 50/host, 200 total | 100/host, 500 total |
| Connection pools (Cassandra) | 30/node | 50/node |
| Connection pools (Redis) | 30 connections, 100 waiting | 50 connections, 200 waiting |
| CORS origins | `localhost:3000` | `*` (must be overridden) |
| Translation config storage | Memory | Auto-selects (Cassandra if available) |
| Translation config cache | Disabled | Enabled |
