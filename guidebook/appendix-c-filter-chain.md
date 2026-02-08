# Appendix C: Complete Filter Chain Execution Order

This appendix documents every request filter in the Aussie API Gateway, organized by layer and execution order. It covers both the Vert.x routing layer (which runs before JAX-RS) and the JAX-RS request filter layer, with enough detail to answer the question: "When my request hits the gateway, what code runs, in what order, and what can stop it?"

Understanding this chain is a prerequisite for debugging any cross-cutting concern -- rate limiting, authentication, access control, CORS, or security headers. If a request is rejected and you do not know which filter rejected it, this appendix is the place to start.

---

## How to Read This Appendix

There are two filter layers in the gateway, and they use opposite priority conventions.

**Vert.x RouteFilters** run at the HTTP routing layer, before Quarkus hands the request to JAX-RS. They are annotated with `@RouteFilter(priority)`. Higher numbers run first. A filter at priority 100 runs before a filter at priority 50.

**JAX-RS filters** run after Vert.x routing completes. They come in two flavors:

- Traditional `ContainerRequestFilter` implementations annotated with `@Provider` and `@Priority(value)`.
- Quarkus RESTEasy Reactive `@ServerRequestFilter(priority = value)` methods.

In both cases, lower numbers run first. A filter at priority 900 runs before a filter at priority 950. When no priority is specified, the default is `Priorities.USER` (5000).

This inversion -- higher-first for Vert.x, lower-first for JAX-RS -- is the single most common source of confusion when reasoning about filter ordering.

---

## Request Flow Diagram

The following diagram traces a standard HTTP request from arrival through both filter layers and into application code. The response path is shown on the right.

```
                          INBOUND REQUEST
                               |
                               v
  =============================================
  |         VERT.X ROUTING LAYER              |
  |         (higher priority = runs first)    |
  =============================================
  |                                           |
  |  [Priority 100] CorsFilter               |
  |    |                                      |
  |    |-- preflight OPTIONS? --> 200/403     |----> RESPONSE (short-circuit)
  |    |-- origin not allowed? --> skip CORS  |
  |    |-- ok? --> add CORS headers, next()   |
  |    v                                      |
  |  [Priority 90] SecurityHeadersFilter      |
  |    |                                      |
  |    |-- always adds headers, calls next()  |
  |    v                                      |
  |  [Priority 50] WebSocketUpgradeFilter     |
  |    |                                      |
  |    |-- not WS upgrade? --> next()         |
  |    |-- WS upgrade? --> WebSocketGateway   |----> RESPONSE (short-circuit)
  |    v                                      |
  |  [Priority 40] WebSocketRateLimitFilter   |
  |    |                                      |
  |    |-- not WS upgrade? --> next()         |
  |    |-- rate limit exceeded? --> 429       |----> RESPONSE (short-circuit)
  |    |-- ok? --> next()                     |
  |    v                                      |
  =============================================
                     |
                     v
  =============================================
  |         JAX-RS FILTER LAYER               |
  |         (lower priority = runs first)     |
  =============================================
  |                                           |
  |  [Priority 900] AuthRateLimitFilter       |
  |    |            (@ServerRequestFilter)    |
  |    |-- not auth endpoint? --> continue    |
  |    |-- locked out? --> 429                |----> RESPONSE (short-circuit)
  |    |-- ok? --> continue                   |
  |    v                                      |
  |  [Priority 900] RequestValidationFilter   |
  |    |            (@Provider + @Priority)   |
  |    |-- payload too large? --> 413         |----> RESPONSE (short-circuit)
  |    |-- headers too large? --> 431         |----> RESPONSE (short-circuit)
  |    |-- ok? --> continue                   |
  |    v                                      |
  |  [Priority 900] ConflictingAuthFilter     |
  |    |            (@Provider + @Priority)   |
  |    |-- both auth + session? --> 400       |----> RESPONSE (short-circuit)
  |    |-- ok? --> continue                   |
  |    v                                      |
  |  [Priority 950] RateLimitFilter           |
  |    |            (@ServerRequestFilter)    |
  |    |-- disabled? --> continue             |
  |    |-- rate limit exceeded? --> 429       |----> RESPONSE (short-circuit)
  |    |-- ok? --> continue (stores decision) |
  |    v                                      |
  |  [Priority 1000] AuthenticationFilter     |
  |    |            (@Provider + @Priority)   |
  |    |-- legacy disabled? --> skip          |
  |    |-- auth failure? --> 401/403          |----> RESPONSE (short-circuit)
  |    |-- ok? --> sets auth context          |
  |    v                                      |
  |  [Priority 5000] AccessControlFilter      |
  |    |            (@ServerRequestFilter)    |
  |    |-- public endpoint? --> continue      |
  |    |-- source not allowed? --> 404        |----> RESPONSE (short-circuit)
  |    |-- ok? --> continue                   |
  |    v                                      |
  =============================================
                     |
                     v
          +---------------------+
          |  APPLICATION CODE   |
          |  (Resource method)  |
          +---------------------+
                     |
                     v
  =============================================
  |         RESPONSE FILTERS                  |
  =============================================
  |                                           |
  |  RateLimitFilter (@ServerResponseFilter)  |
  |    |-- adds X-RateLimit-* headers         |
  |    v                                      |
  =============================================
                     |
                     v
                  RESPONSE
```

A few things to note about this diagram. First, the WebSocket filters (priorities 50 and 40) only affect WebSocket upgrade requests. For standard HTTP requests, both call `ctx.next()` immediately and pass through in microseconds. Second, the three filters at priority 900 (`AuthRateLimitFilter`, `RequestValidationFilter`, and `ConflictingAuthFilter`) all compute to `Priorities.AUTHENTICATION - 100`, which is `1000 - 100 = 900`. Their relative ordering among themselves is not guaranteed by the spec and depends on container implementation details. Third, the response path is considerably simpler than the request path -- only `RateLimitFilter` has a response filter, and it only adds headers.

---

## Layer 1: Vert.x RouteFilters

### CorsFilter -- Priority 100

**File:** `api/src/main/java/aussie/adapter/in/http/CorsFilter.java`

The CORS filter is the first code that touches every inbound request. It runs at the Vert.x routing layer rather than at the JAX-RS layer because the gateway proxies requests that may never reach a JAX-RS resource method. A JAX-RS response filter would miss those proxied requests entirely. By running at the Vert.x layer, the filter ensures that every response -- proxied, error, or normal -- carries the correct CORS headers.

The filter reads a global `GatewayCorsConfig` from CDI. If CORS is disabled or the config is not resolvable, it calls `rc.next()` and passes through. If the request carries no `Origin` header, it is not a CORS request and passes through. For CORS requests, the filter validates the origin against the configured allowed origins, checks the request method against allowed methods, and adds the appropriate response headers (`Access-Control-Allow-Origin`, `Access-Control-Allow-Methods`, `Access-Control-Allow-Headers`, `Access-Control-Allow-Credentials`, `Access-Control-Max-Age`). When credentials are allowed, the filter echoes the specific origin rather than returning `*`, and sets `Vary: Origin` to prevent cache poisoning.

**What it modifies:** Response headers on the `RoutingContext`.

**Short-circuit conditions:**
- Preflight `OPTIONS` request with a valid origin: responds with 200 and CORS headers. Does not call `next()`.
- Preflight `OPTIONS` request with a disallowed origin or method: responds with 403. Does not call `next()`.

For non-preflight requests, the filter always calls `next()`, even if the origin is not allowed (it simply omits the CORS headers, and the browser enforces the policy on the client side).

---

### SecurityHeadersFilter -- Priority 90

**File:** `api/src/main/java/aussie/adapter/in/http/SecurityHeadersFilter.java`

This filter adds OWASP-recommended security response headers to every response. It runs after CORS (priority 90 < 100) so that CORS headers set by `CorsFilter` are not overwritten. The filter reads a `SecurityHeadersConfig` from CDI and sets five headers unconditionally and two optionally:

| Header | Default | Purpose |
|--------|---------|---------|
| `X-Content-Type-Options` | `nosniff` | Prevents MIME type sniffing |
| `X-Frame-Options` | `DENY` | Blocks clickjacking via iframes |
| `Content-Security-Policy` | `default-src 'none'` | Blocks all resource loading by default |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | Limits referrer leakage |
| `X-Permitted-Cross-Domain-Policies` | `none` | Blocks Flash/PDF cross-domain access |
| `Strict-Transport-Security` | (optional) | HSTS -- only when behind TLS termination |
| `Permissions-Policy` | (optional) | Controls browser feature access |

**What it modifies:** Response headers on the `RoutingContext`.

**Short-circuit conditions:** None. This filter always calls `rc.next()`. If the config is not resolvable or is disabled, it passes through without adding headers.

---

### WebSocketUpgradeFilter -- Priority 50

**File:** `api/src/main/java/aussie/adapter/in/websocket/WebSocketUpgradeFilter.java`

This filter intercepts HTTP requests that carry WebSocket upgrade headers (`Upgrade: websocket` and `Connection: upgrade`) and routes them to the `WebSocketGateway` handler before JAX-RS processing. Without this filter, WebSocket upgrade requests would fall through to JAX-RS resource methods, which do not know how to handle them.

The filter inspects the request path to determine routing mode. Paths starting with `/gateway/` are handled as gateway-mode WebSocket connections (routed by the gateway's internal route table). Other non-reserved paths are handled as pass-through WebSocket connections (proxied directly to the upstream service identified by the first path segment). Reserved paths (`admin`, `q`) are passed through to normal routing, where they will 404.

**What it modifies:** Nothing directly -- it delegates to `WebSocketGateway.handleGatewayUpgrade()` or `WebSocketGateway.handlePassThroughUpgrade()`, which take over the connection entirely.

**Short-circuit conditions:**
- WebSocket upgrade to a gateway path: delegates to `WebSocketGateway`. Does not call `next()`.
- WebSocket upgrade to a pass-through path: delegates to `WebSocketGateway`. Does not call `next()`.
- WebSocket upgrade to a reserved path: calls `next()` (will 404 via normal routing).
- Not a WebSocket upgrade: calls `next()`.

---

### WebSocketRateLimitFilter -- Priority 40

**File:** `api/src/main/java/aussie/adapter/in/websocket/WebSocketRateLimitFilter.java`

This filter enforces connection-level rate limits on WebSocket upgrade requests. It runs at the Vert.x layer (rather than JAX-RS) because WebSocket upgrades are intercepted by `WebSocketUpgradeFilter` at priority 50 and never reach JAX-RS. The priority of 40 ensures rate limiting runs before the upgrade filter at 50 -- since higher priorities run first in Vert.x, this means the rate limit check at 40 actually runs *after* the upgrade filter at 50.

Wait -- this deserves clarification, because it is the kind of ordering subtlety that causes real bugs. In the Vert.x `@RouteFilter` convention, higher numbers run first. So the actual Vert.x execution order is:

1. CorsFilter (100) -- runs first
2. SecurityHeadersFilter (90) -- runs second
3. WebSocketUpgradeFilter (50) -- runs third
4. WebSocketRateLimitFilter (40) -- runs fourth

This means `WebSocketUpgradeFilter` at priority 50 runs *before* `WebSocketRateLimitFilter` at priority 40. The code comments in `WebSocketRateLimitFilter` state that "Priority 40 ensures this runs after CORS (100) but before WebSocket upgrade (50)" -- but that comment describes the *desired* behavior using a convention where lower numbers run first, which is the opposite of how Vert.x `@RouteFilter` works.

In practice, this ordering issue is mitigated because `WebSocketUpgradeFilter` hands off to `WebSocketGateway` without calling `next()`, which means `WebSocketRateLimitFilter` never executes for WebSocket requests that `WebSocketUpgradeFilter` intercepts. The rate limit filter only runs for requests that pass through the upgrade filter (non-WebSocket requests or reserved paths), where its WebSocket detection check (`isWebSocketUpgrade`) causes it to call `next()` immediately.

The net effect is that WebSocket rate limiting as implemented in this filter may not apply to WebSocket upgrade requests that are handled by `WebSocketUpgradeFilter`. Rate limiting for WebSocket connections likely occurs inside the `WebSocketGateway` itself or through a separate mechanism.

For requests that do reach this filter and are identified as WebSocket upgrades, the filter extracts a service ID from the request path, identifies the client (using the same session > bearer > API key > IP priority chain as the HTTP rate limiter), and checks the connection rate limit via the `RateLimiter` service. The rate limit key uses the format `aussie:ratelimit:ws:conn:{serviceId}:{clientId}`, separate from the HTTP rate limit namespace.

**What it modifies:** On allowed requests, sets OpenTelemetry span attributes for rate limit status. On rejected requests, writes a 429 response with `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset` headers. Dispatches a `SecurityEvent.RateLimitExceeded` event and records metrics.

**Short-circuit conditions:**
- Not a WebSocket upgrade request: calls `next()`.
- Rate limiting disabled: calls `next()`.
- Reserved path (`admin`, `q`): calls `next()`.
- Rate limit exceeded: responds with HTTP 429 and terminates the request. Does not call `next()`.
- Rate limit check fails (error): calls `next()` (fail-open).

---

## Layer 2: JAX-RS Filters

After the Vert.x RouteFilter chain completes (assuming no filter short-circuited), the request enters the JAX-RS filter chain. The filters below are listed in execution order -- lowest priority number first.

### AuthRateLimitFilter -- Priority 900 (Priorities.AUTHENTICATION - 100)

**File:** `api/src/main/java/aussie/system/filter/AuthRateLimitFilter.java`

This filter protects authentication endpoints against brute force attacks by tracking failed authentication attempts and locking out clients that exceed the threshold. It is separate from the general-purpose `RateLimitFilter` because authentication rate limiting and HTTP rate limiting are fundamentally different problems. Authentication rate limiting tracks failed attempts per account (or per IP targeting an account), while HTTP rate limiting tracks total requests per client. Conflating them would mean either the API rate limits are too tight for normal usage or the brute force protection is too loose for credential stuffing.

The filter runs at `Priorities.AUTHENTICATION - 100` (numeric value 900), which is before both the general rate limiter (950) and the authentication filter (1000). This ordering is critical: a locked-out client is rejected before it consumes an HTTP rate limit token, preventing the lockout from counting against the legitimate user's quota when they recover their credentials.

The filter only applies to authentication-related endpoints: paths starting with `/auth`, `/admin/sessions`, or `/admin/api-keys`. All other requests pass through without evaluation.

The filter is conditionally enabled via the `@IfBuildProperty` annotation. When `aussie.auth.rate-limit.enabled` is `false`, the entire bean is excluded from the CDI container at build time.

**What it returns:** `Uni<Response>` -- returns `null` (via `Uni.createFrom().nullItem()`) to continue processing, or a `Response` to abort. Sets the rate limit result as a request context property (`aussie.auth.ratelimit.result`) for downstream filters to inspect.

**Short-circuit conditions:**
- Auth rate limiting disabled (`config.enabled()` is false): returns null, continues.
- Request path is not an auth endpoint: returns null, continues.
- Client is locked out: returns a 429 response with `Retry-After` header and an RFC 9457 Problem Details body. Dispatches a `SecurityEvent.AuthenticationLockout` event.

---

### RequestValidationFilter -- Priority 900 (Priorities.AUTHENTICATION - 100)

**File:** `api/src/main/java/aussie/system/filter/RequestValidationFilter.java`

This filter validates inbound request size constraints before any further processing. It checks the `Content-Length` header against a configured maximum payload size and validates header sizes. This is a defense-in-depth measure that catches oversized requests at the application layer, complementing any size limits enforced by the load balancer or reverse proxy in front of the gateway.

The filter delegates to `RequestSizeValidator` for the actual validation logic and maps the result to the appropriate HTTP error response.

**What it modifies:** Nothing on success. On failure, throws a `GatewayProblem` exception.

**Short-circuit conditions:**
- Payload too large: throws `GatewayProblem.payloadTooLarge()`, which maps to HTTP 413.
- Headers too large: throws `GatewayProblem.headerTooLarge()`, which maps to HTTP 431.
- Other validation failure: throws `GatewayProblem.badRequest()`, which maps to HTTP 400.

---

### ConflictingAuthFilter -- Priority 900 (Priorities.AUTHENTICATION - 100)

**File:** `api/src/main/java/aussie/adapter/in/auth/ConflictingAuthFilter.java`

This filter rejects requests that carry both an `Authorization` header and a session cookie simultaneously. Allowing both authentication mechanisms in a single request creates ambiguity -- which credential should the gateway honor? -- and opens the door to confused deputy attacks where a session-authenticated browser is tricked into sending an API request that also carries an attacker-controlled `Authorization` header.

The filter checks whether sessions are enabled (via `SessionConfig`), whether the request has an `Authorization` header, and whether the request has a session cookie (via `SessionCookieManager`). If both are present, it aborts with 400 Bad Request.

**What it modifies:** Nothing on success. On failure, aborts the request with a JSON error response.

**Short-circuit conditions:**
- Session config not resolvable: returns without action (passes through).
- Sessions disabled: returns without action.
- Cookie manager not resolvable: returns without action.
- Both `Authorization` header and session cookie present: aborts with 400 Bad Request.

---

### RateLimitFilter -- Priority 950 (Priorities.AUTHENTICATION - 50)

**File:** `api/src/main/java/aussie/system/filter/RateLimitFilter.java`

This is the general-purpose HTTP rate limiter. It runs after auth rate limiting (900) but before authentication (1000), so that excessive traffic is rejected before incurring the cost of JWT validation, token introspection, or session lookup. The filter identifies the client using a four-layer priority chain (session ID > bearer token hash > API key ID > client IP), resolves the effective rate limit by consulting the service registry and rate limit resolver (endpoint config > service config > platform defaults, capped at the platform maximum), and checks the limit against the configured backend (Redis or in-memory).

The filter stores the `RateLimitDecision` as a request context property (`aussie.ratelimit.decision`) so that the companion `@ServerResponseFilter` can add `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset` headers to the response without recomputing the rate limit status.

On rate limit exceeded, the filter records metrics, dispatches a `SecurityEvent.RateLimitExceeded` event, and sets OpenTelemetry span attributes. Notably, it sets `StatusCode.OK` on the span rather than `StatusCode.ERROR`, because rate limiting is expected behavior -- the system working correctly -- not a failure condition. Setting `ERROR` would trigger error-rate alerts and pollute the error budget.

**What it returns:** `Uni<Response>` -- returns `null` to continue processing, or a 429 `Response` with `Retry-After` header and RFC 9457 Problem Details body.

**Short-circuit conditions:**
- Rate limiting disabled: returns null, continues.
- Rate limit exceeded: returns a 429 response.
- Rate limit check fails (Redis error): fail-open, returns null, continues. Logs a warning.

---

### AuthenticationFilter -- Priority 1000 (Priorities.AUTHENTICATION)

**File:** `api/src/main/java/aussie/system/filter/AuthenticationFilter.java`

This is the legacy authentication filter, **deprecated** in favor of Quarkus Security integration via `ApiKeyAuthenticationMechanism` and `ApiKeyIdentityProvider`. It is disabled by default and only activates when `aussie.auth.use-legacy-filter=true`. It is documented here for completeness and because some deployments with custom `AuthenticationProvider` SPI implementations may still use it.

When enabled, the filter collects all available `AuthenticationProvider` instances from CDI, sorts them by priority (highest first), and iterates until one returns a non-`Skip` result. On `Success`, it stores the `AuthenticationContext` as a request property. On `Failure`, it aborts with the provider's specified status code (typically 401 or 403). If all providers skip and the request reaches the end of the chain, it aborts with 401 Unauthorized.

The filter can be scoped to admin paths only (`aussie.auth.admin-paths-only=true`, the default), in which case non-admin requests pass through without authentication.

**What it modifies:** On success, sets the `aussie.auth.context` request property with the `AuthenticationContext`.

**Short-circuit conditions:**
- Legacy filter disabled (`use-legacy-filter=false`, the default): returns without action.
- Admin-paths-only enabled and request is not to an admin path: returns without action.
- Auth disabled entirely: returns without action (logs a warning).
- No providers available: aborts with 500 Internal Server Error.
- Provider returns `Failure`: aborts with the failure's status code (typically 401 or 403).
- All providers return `Skip`: aborts with 401 Unauthorized.

---

### AccessControlFilter -- Priority 5000 (default, Priorities.USER)

**File:** `api/src/main/java/aussie/system/filter/AccessControlFilter.java`

This filter enforces the gateway's visibility model. Every registered service and endpoint has a visibility setting: `PUBLIC` or `PRIVATE`. Public endpoints pass through. Private endpoints require the request source (IP, forwarded-for address) to be in the service's allowed source list. The filter runs at the default priority (5000), which is after authentication, rate limiting, and all other explicit-priority filters. This ordering is intentional: rate-limited clients receive 429 rather than 404, which prevents leaking information about which paths exist to clients that should be getting rate-limited.

The filter resolves the target service by parsing the request path. Gateway-mode requests (`/gateway/...`) are resolved via the route table. Pass-through requests (`/{serviceId}/...`) are resolved by looking up the service registration. Reserved paths (`admin`, `gateway`, `q`) are skipped. When a route or service is found, the filter extracts the request source via `SourceIdentifierExtractor` (which consults the trusted proxy validator before reading forwarding headers) and evaluates access via `AccessControlEvaluator`.

When access is denied, the filter throws `GatewayProblem.notFound("Not found")`, returning 404 rather than 403. This is a deliberate security choice: a 403 response confirms the resource exists; a 404 reveals nothing.

**What it returns:** `Uni<Response>` -- returns `null` to continue processing, or throws a `GatewayProblem` exception.

**Short-circuit conditions:**
- Reserved path: returns null, continues.
- No matching service or route: returns null, continues (lets the request 404 via normal JAX-RS routing).
- Public endpoint: returns null, continues.
- Source not allowed on private endpoint: throws `GatewayProblem.notFound()`, which maps to HTTP 404.

---

## Response Filters

### RateLimitFilter (Response) -- @ServerResponseFilter

**File:** `api/src/main/java/aussie/system/filter/RateLimitFilter.java` (same class as the request filter, line 144)

The only response filter in the chain. It reads the `RateLimitDecision` from the request context property set during the request phase and, if present and allowed, adds three headers to the response:

- `X-RateLimit-Limit` -- the configured limit for this bucket
- `X-RateLimit-Remaining` -- tokens remaining in the current window
- `X-RateLimit-Reset` -- epoch seconds when the bucket resets

These headers are only added when `config().includeHeaders()` is true (the default). Some organizations disable them for public-facing APIs to avoid revealing rate limit internals to potential attackers.

This filter never short-circuits. It modifies the response and returns.

---

## Priority Reference Table

The following table lists every filter in numeric execution order across both layers. The "Numeric Priority" column shows the actual number used for ordering. The "Runs" column indicates position within the chain (1 = first).

### Vert.x Layer (higher number = runs first)

| Runs | Priority | Class | Package | Short-Circuits? |
|------|----------|-------|---------|-----------------|
| 1 | 100 | `CorsFilter` | `adapter.in.http` | Yes (OPTIONS preflight) |
| 2 | 90 | `SecurityHeadersFilter` | `adapter.in.http` | No |
| 3 | 50 | `WebSocketUpgradeFilter` | `adapter.in.websocket` | Yes (WS upgrade) |
| 4 | 40 | `WebSocketRateLimitFilter` | `adapter.in.websocket` | Yes (429 on WS rate limit) |

### JAX-RS Layer (lower number = runs first)

| Runs | Priority | Constant Expression | Class | Package | Short-Circuits? |
|------|----------|---------------------|-------|---------|-----------------|
| 5 | 900 | `AUTH - 100` | `AuthRateLimitFilter` | `system.filter` | Yes (429 on lockout) |
| 6 | 900 | `AUTH - 100` | `RequestValidationFilter` | `system.filter` | Yes (413, 431, 400) |
| 7 | 900 | `AUTH - 100` | `ConflictingAuthFilter` | `adapter.in.auth` | Yes (400 on conflict) |
| 8 | 950 | `AUTH - 50` | `RateLimitFilter` | `system.filter` | Yes (429 on rate limit) |
| 9 | 1000 | `AUTH` | `AuthenticationFilter` | `system.filter` | Yes (401, 403, 500) |
| 10 | 5000 | (default) | `AccessControlFilter` | `system.filter` | Yes (404 on denied) |

Note: Filters at the same priority (900) have no guaranteed relative ordering. The container may execute them in any order. In practice, Quarkus tends to order them by discovery order (classpath scanning), but this is an implementation detail, not a contract.

---

## Why This Ordering Matters

The filter chain encodes a set of policy decisions about what information to reveal, what resources to protect, and what work to avoid. Each ordering choice has a specific rationale.

**CORS before everything (100).** CORS preflight responses must include the correct headers even when the gateway rejects the subsequent request. If CORS ran after rate limiting, a rate-limited preflight would return 429 without CORS headers, and the browser would interpret this as a CORS failure rather than a rate limit -- a confusing error for the API consumer.

**Security headers before application logic (90).** Security headers must be present on every response, including error responses generated by downstream filters. If security headers ran after rate limiting, a 429 response would lack `X-Content-Type-Options` and `Content-Security-Policy`.

**Auth rate limiting before general rate limiting (900 vs. 950).** A client locked out due to brute force attacks should be rejected before consuming a general rate limit token. Without this ordering, a lockout would also decrement the legitimate user's rate limit budget, compounding the impact of the attack.

**Rate limiting before authentication (950 vs. 1000).** Authentication is expensive (cryptographic operations, external calls). Rate limiting is cheap (one hash map or Redis lookup). When an attacker sends 10,000 requests per second, rejecting 9,900 of them via rate limiting before performing any authentication saves substantial compute.

**Access control after everything (5000).** Access control runs last because it depends on the request being otherwise valid. A rate-limited client should receive 429, not 404 -- the rate limit response tells the client to retry later, while the 404 would suggest the resource does not exist. More importantly, running access control after authentication means the `AccessControlEvaluator` can use authenticated identity information if available, though the current implementation evaluates based on source IP and service configuration rather than authenticated principal.

---

## WebSocket Path: A Separate Chain

WebSocket upgrade requests follow a fundamentally different path through the filter chain. The Vert.x RouteFilters process them (CORS headers and security headers are applied), but `WebSocketUpgradeFilter` at priority 50 intercepts them and delegates to `WebSocketGateway` without calling `next()`. This means WebSocket upgrade requests never reach:

- `WebSocketRateLimitFilter` (priority 40, runs after 50)
- Any JAX-RS filter
- Any JAX-RS resource method

WebSocket rate limiting and authentication are handled inside the `WebSocketGateway` itself, outside the standard filter chain. This is a consequence of the Vert.x priority ordering: because higher numbers run first and the upgrade filter (50) runs before the rate limit filter (40), the upgrade intercepts the request before rate limiting can check it.

```
  WebSocket Upgrade Request
         |
         v
  [100] CorsFilter ------------> adds CORS headers
         |
         v
  [90]  SecurityHeadersFilter -> adds security headers
         |
         v
  [50]  WebSocketUpgradeFilter -> intercepts, delegates to WebSocketGateway
                                  (does NOT call next())

  [40]  WebSocketRateLimitFilter -- never reached for WS upgrades
         ...
  JAX-RS filters               -- never reached for WS upgrades
```

This separation is clean in practice -- WebSocket and HTTP traffic flow through different handlers after the initial Vert.x filters -- but it means that changes to WebSocket rate limiting or authentication must be made inside `WebSocketGateway`, not in the standard filter chain.

---

## Debugging Guide

When a request is rejected and you need to identify which filter is responsible, use the following checklist:

1. **Check the HTTP status code.** Each filter produces a characteristic status:
   - 200 with CORS headers only: `CorsFilter` (preflight)
   - 400 with `conflicting_authentication`: `ConflictingAuthFilter`
   - 400/413/431 with Problem Details: `RequestValidationFilter`
   - 401/403 with auth error: `AuthenticationFilter`
   - 404 with "Not found": `AccessControlFilter`
   - 429 with `Retry-After`: `RateLimitFilter`, `AuthRateLimitFilter`, or `WebSocketRateLimitFilter`

2. **Check for rate limit headers.** If the response contains `X-RateLimit-Limit`, the request reached `RateLimitFilter` (either its request or response phase). If these headers are absent and the status is 429, check whether the 429 came from `AuthRateLimitFilter` (which includes `X-Auth-Lockout-Key` and `X-Auth-Lockout-Reset` when `includeHeaders` is enabled).

3. **Check OpenTelemetry spans.** The `aussie.rate_limit.limited`, `aussie.rate_limit.type`, and `aussie.auth.rate_limited` span attributes indicate which rate limiter made the decision.

4. **Check the response body format.** `AccessControlFilter`, `RateLimitFilter`, and `RequestValidationFilter` use RFC 9457 Problem Details format (JSON with `type`, `title`, `status`, `detail` fields). `AuthenticationFilter` and `ConflictingAuthFilter` use a simpler `{"error": "..."}` format. `CorsFilter` and `SecurityHeadersFilter` never generate response bodies on their own.

5. **Enable DEBUG logging** for the relevant filter class. Every filter logs its decision at DEBUG or TRACE level, including the reason for pass-through or rejection.
