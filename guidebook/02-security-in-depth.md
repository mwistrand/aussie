# Chapter 2: Security in Depth -- Layers That a Senior Might Skip

A senior engineer builds authentication. A staff engineer builds the ten layers around it that make authentication survivable when one layer fails. This chapter walks through the security architecture of the Aussie API Gateway, layer by layer, examining not just what was built, but why each layer exists and what happens when it is absent.

Every section follows the same structure: what was done, why it matters, what a senior engineer would plausibly skip, and what trade-offs remain.

---

## 2.1 SSRF Protection

**File:** `api/src/main/java/aussie/adapter/in/validation/UrlValidator.java`

When an API team registers a service with Aussie, they provide a `baseUrl` -- the upstream address the gateway will proxy requests to. Without validation, an attacker who gains access to the registration API can point a service at `http://169.254.169.254/latest/meta-data/` and use the gateway itself as a proxy to exfiltrate cloud instance credentials.

### What Was Done

The `UrlValidator` class blocks three categories of dangerous addresses at registration time:

```java
// UrlValidator.java, lines 72-83
private static boolean isBlockedHost(String host) {
    if (host.equalsIgnoreCase("localhost") || host.equals("0.0.0.0") || host.equals("::")) {
        return true;
    }

    try {
        final var addr = InetAddress.getByName(host);
        return addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress();
    } catch (UnknownHostException e) {
        return false;
    }
}
```

This blocks:

- **Loopback addresses** (`127.0.0.0/8`, `::1`, `localhost`) -- prevents the gateway from calling itself or other processes on the same host.
- **Link-local addresses** (`169.254.0.0/16`) -- this is the critical one. On AWS, `169.254.169.254` is the instance metadata endpoint. On GCP, it is `169.254.169.254` with a `Metadata-Flavor: Google` header. On Azure, it is `169.254.169.254` with a `Metadata: true` header. All three clouds expose IAM credentials, user data scripts, and network configuration through this address.
- **Wildcard/any addresses** (`0.0.0.0`, `::`) -- prevents binding to "all interfaces" which could be exploited for service confusion.

The validation runs inside `ServiceRegistrationRequest.toModel()` (line 98 of `api/src/main/java/aussie/adapter/in/dto/ServiceRegistrationRequest.java`):

```java
// ServiceRegistrationRequest.java, line 98
UrlValidator.validateServiceUrl(baseUrl, "baseUrl"),
```

This ensures the check happens at the domain boundary -- before the URL enters the system's internal model.

### What a Senior Might Skip

A senior would likely validate that the URL is syntactically correct (has a scheme, has a host). They would be less likely to enumerate the specific IP ranges that are dangerous in cloud environments, because SSRF through an API gateway requires understanding that the gateway itself becomes the attack vector. The gateway is not fetching arbitrary user-supplied URLs like a webhook system -- it is proxying to a configured address -- so the threat feels less immediate.

Note that site-local addresses (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`) are deliberately *allowed*. The Javadoc on line 66 explains why: internal service-to-service routing is a legitimate use case. An overly aggressive blocklist that rejects RFC 1918 addresses would break the primary function of an internal API gateway.

### Trade-offs

The `UnknownHostException` catch on line 81 returns `false` (not blocked), which means a hostname that fails DNS resolution at registration time is permitted through. This is a deliberate choice: the alternative -- blocking on DNS failure -- would reject registrations during transient DNS outages. The risk is that an attacker could register a hostname that resolves to a link-local address *later*, after registration. Mitigating this would require runtime re-resolution on every proxied request, which introduces latency and its own class of DNS rebinding attacks.

---

## 2.2 Trusted Proxy Validation

**File:** `api/src/main/java/aussie/core/service/common/TrustedProxyValidator.java`
**File:** `api/src/main/java/aussie/core/service/common/SourceIdentifierExtractor.java`

Every access control decision in the gateway starts with the question: "Who is making this request?" In an environment with load balancers and reverse proxies, the answer depends on headers like `X-Forwarded-For` and `Forwarded`. But those headers are trivially spoofable -- any HTTP client can set them. Trusting them unconditionally is equivalent to letting clients choose their own identity.

### What Was Done

The `TrustedProxyValidator` only honors forwarding headers when the direct TCP connection originates from a configured proxy CIDR:

```java
// TrustedProxyValidator.java, lines 50-62
public boolean shouldTrustForwardingHeaders(String socketIp) {
    if (!config.enabled()) {
        return true;
    }
    if (socketIp == null || socketIp.isEmpty()) {
        return false;
    }
    final var proxies = config.proxies();
    if (proxies.isEmpty() || proxies.get().isEmpty()) {
        return false;
    }
    return matchesAny(socketIp, proxies.get());
}
```

The `SourceIdentifierExtractor` consults this validator before reading any forwarding headers:

```java
// SourceIdentifierExtractor.java, lines 44-56
public SourceIdentifier extract(ContainerRequestContext request, String socketIp) {
    final var trustHeaders = trustedProxyValidator.shouldTrustForwardingHeaders(socketIp);

    var ipAddress = trustHeaders ? extractIpFromHeaders(request) : null;
    if (ipAddress == null || ipAddress.isEmpty()) {
        ipAddress = socketIp != null ? socketIp : extractFallbackIp(request);
    }

    var host = trustHeaders ? extractHost(request) : Optional.<String>empty();
    var forwardedFor = trustHeaders ? extractForwardedFor(request) : Optional.<String>empty();

    return new SourceIdentifier(ipAddress, host, forwardedFor);
}
```

When the socket IP is not in the trusted proxy list, the extractor falls back to the raw socket address. The client's claimed `X-Forwarded-For` is ignored entirely.

The CIDR matching itself is carefully implemented. Line 153 of `TrustedProxyValidator.java` shows the IP parsing function refuses to resolve hostnames:

```java
// TrustedProxyValidator.java, lines 153-158
private static byte[] parseIpAddress(String ip) {
    if (!isIpAddressLiteral(ip)) {
        return null;
    }
    return parseIpAddressLiteral(ip);
}
```

The `isIpAddressLiteral` check on line 172 verifies that the input contains only digits and dots (IPv4) or colons (IPv6) before passing it to `InetAddress.getByName`. This prevents DNS resolution side effects when validating socket addresses.

### What a Senior Might Skip

A senior would typically use `X-Forwarded-For` without a trust check, assuming the load balancer is correctly configured. This works until the gateway is deployed behind a different infrastructure, or exposed directly during an incident, or tested locally without a proxy. The trust validation makes the gateway's behavior explicit rather than dependent on deployment topology.

### Trade-offs

When `config.enabled()` is false, the validator returns `true` unconditionally -- all forwarding headers are trusted. This is the out-of-the-box default to avoid breaking local development. The platform team must explicitly enable validation and configure their proxy CIDRs for production. This is documented, but it means a deployment that forgets to enable it silently trusts all forwarding headers.

---

## 2.3 Production Startup Guards

**File:** `api/src/main/java/aussie/adapter/in/auth/NoopAuthGuard.java`

Aussie has a `dangerous-noop` authentication mode that disables all authentication. It exists for local development and integration testing. If it reaches production, every endpoint in the gateway is unprotected.

### What Was Done

The `NoopAuthGuard` observes the Quarkus startup event and throws an `IllegalStateException` if the dangerous-noop flag is enabled in production mode:

```java
// NoopAuthGuard.java, lines 27-33
void onStart(@Observes StartupEvent event) {
    if (isDangerousNoopEnabled() && currentLaunchMode() == LaunchMode.NORMAL) {
        LOG.error("aussie.auth.dangerous-noop=true is not allowed in production mode");
        throw new IllegalStateException("aussie.auth.dangerous-noop=true is not allowed in production mode. "
                + "This setting disables all authentication. "
                + "Remove this setting or run in dev/test mode.");
    }
}
```

The check uses `LaunchMode.NORMAL` (production mode) rather than checking for the absence of dev mode. Quarkus has three launch modes: `NORMAL`, `DEVELOPMENT`, and `TEST`. The guard only blocks NORMAL, which means the noop auth works freely in development and test contexts.

The config is read dynamically via `ConfigProvider.getConfig()` (line 41) rather than injected via `@ConfigMapping`. This is intentional -- the Javadoc on line 39 explains that dynamic reading ensures test profiles can override the value.

### What a Senior Might Skip

A senior would likely name the flag something like `disable-auth` and trust that nobody would set it in production. The word "dangerous" in the property name is deliberate -- it is a forcing function in code review. But naming alone is not sufficient. Without the startup guard, the flag could reach production through a misconfigured environment variable, a copy-pasted test configuration, or an infrastructure-as-code template that was not properly parameterized.

The key design decision here is to **fail at deploy time, not at request time**. A runtime check that logs a warning on each request would generate noise but would not prevent damage. An `IllegalStateException` during startup prevents the pod from entering the load balancer's healthy pool. In Kubernetes, this means the deployment fails and rolls back.

### Trade-offs

The guard checks `LaunchMode.current()` at startup, which is a static check. If Quarkus ever changes how launch modes are determined, or if a container orchestrator somehow masks the launch mode, the guard could be bypassed. The mitigation is that the guard is a defense-in-depth measure -- it is not the only thing preventing noop auth from reaching production. Deployment pipelines should also enforce this through configuration validation.

---

## 2.4 Default-Deny Visibility

**File:** `api/src/main/java/aussie/core/model/routing/EndpointVisibility.java`
**File:** `api/src/main/java/aussie/core/model/auth/GatewaySecurityConfig.java`
**File:** `api/src/main/java/aussie/core/service/routing/ServiceRegistrationValidator.java`
**File:** `api/src/main/java/aussie/core/service/auth/AccessControlEvaluator.java`

The visibility model in Aussie has two states: `PUBLIC` and `PRIVATE`. There is no `INTERNAL`, no `RESTRICTED`, no graduated access levels. This simplicity is the point.

### What Was Done

`EndpointVisibility` is an enum with exactly two values:

```java
// EndpointVisibility.java, lines 1-13
public enum EndpointVisibility {
    PUBLIC,
    PRIVATE
}
```

When a service is registered without specifying `defaultVisibility`, the DTO defaults to `PRIVATE`:

```java
// ServiceRegistrationRequest.java, lines 63-65
var defaultVis = defaultVisibility != null
        ? EndpointVisibility.valueOf(defaultVisibility.toUpperCase())
        : EndpointVisibility.PRIVATE;
```

The gateway also enforces a platform-level guardrail. The `GatewaySecurityConfig` interface exposes a `publicDefaultVisibilityEnabled()` flag (line 13 of `GatewaySecurityConfig.java`). When this is false -- the default -- the `ServiceRegistrationValidator` rejects any service that attempts to set `PUBLIC` as its default visibility:

```java
// ServiceRegistrationValidator.java, lines 33-41
public ValidationResult validate(ServiceRegistration registration) {
    if (EndpointVisibility.PUBLIC.equals(registration.defaultVisibility())
            && !securityConfig.publicDefaultVisibilityEnabled()) {
        return ValidationResult.invalid(
                "PUBLIC default visibility is not allowed by gateway policy. "
                        + "Set defaultVisibility to PRIVATE or contact your gateway administrator.",
                403);
    }
    return ValidationResult.valid();
}
```

The `AccessControlEvaluator` enforces this at request time. Public endpoints pass through. Private endpoints require the source to be in an allowed list:

```java
// AccessControlEvaluator.java, lines 54-64
public boolean isAllowed(
        SourceIdentifier source, RouteLookupResult route, Optional<ServiceAccessConfig> serviceConfig) {
    if (EndpointVisibility.PUBLIC.equals(route.visibility())) {
        return true;
    }
    return isSourceAllowed(source, serviceConfig);
}
```

And when access is denied, the `AccessControlFilter` returns 404, not 403:

```java
// AccessControlFilter.java, lines 114-117
if (!isAllowed) {
    throw GatewayProblem.notFound("Not found");
}
```

This hides the existence of private resources from unauthorized users. A 403 response confirms the resource exists; a 404 reveals nothing.

### What a Senior Might Skip

A senior would likely default to `PUBLIC` and require teams to opt into `PRIVATE` for sensitive endpoints. This is the more common pattern, and it works for applications where most endpoints are public-facing. But in a gateway context, where dozens of teams register services, the failure mode is different: a team that forgets to configure visibility accidentally exposes their API to the internet. Default-deny inverts this: forgetting to configure visibility means the endpoint is invisible to external traffic.

The platform-level guardrail (`publicDefaultVisibilityEnabled`) is the layer a senior would almost certainly skip. It means the platform team can enforce a security posture across all services without relying on each API team to make the right choice.

### Trade-offs

Default-deny creates friction for API teams. Every public endpoint must be explicitly marked. This is a support cost. Teams will open tickets asking why their newly registered service returns 404 for everything. The error message in the validator (line 38) tries to be helpful: "Set defaultVisibility to PRIVATE or contact your gateway administrator." But the operational cost is real and must be weighed against the security benefit.

---

## 2.5 Token Hashing for Rate Limit Keys

**File:** `api/src/main/java/aussie/core/util/SecureHash.java`
**File:** `api/src/main/java/aussie/system/filter/RateLimitFilter.java`

Rate limiting requires identifying clients. The most precise identifier is the bearer token or API key. But storing raw tokens in rate limit state -- whether in-memory, Redis, or Cassandra -- creates a credential exfiltration vector. Anyone with read access to the rate limiter's storage can harvest live tokens.

### What Was Done

The `SecureHash` utility provides truncated SHA-256 hashing:

```java
// SecureHash.java, lines 33-45
public static String truncatedSha256(String input, int hexChars) {
    if (hexChars < 1 || hexChars > MAX_HEX_CHARS) {
        throw new IllegalArgumentException(
            "hexChars must be between 1 and " + MAX_HEX_CHARS + ", got " + hexChars);
    }
    try {
        final var digest = MessageDigest.getInstance("SHA-256");
        final var hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        final var fullHex = HexFormat.of().formatHex(hashBytes);
        return fullHex.substring(0, hexChars);
    } catch (NoSuchAlgorithmException e) {
        throw new AssertionError("SHA-256 must be available per Java spec", e);
    }
}
```

The `RateLimitFilter` uses this to hash bearer tokens before building rate limit keys:

```java
// RateLimitFilter.java, lines 219-225
private Optional<String> extractAuthHeaderHash(HttpServerRequest request) {
    final var auth = request.getHeader("Authorization");
    if (auth != null && auth.startsWith("Bearer ")) {
        return Optional.of("bearer:" + hashToken(auth.substring(7)));
    }
    return Optional.empty();
}
```

And the hash function truncates to 16 hex characters (64 bits):

```java
// RateLimitFilter.java, lines 308-310
private String hashToken(String token) {
    return SecureHash.truncatedSha256(token, 16);
}
```

The same pattern appears in the `AuthRateLimitFilter` for hashing client IDs in security events:

```java
// AuthRateLimitFilter.java, lines 237-242
private String hashClientId(String clientId) {
    if (clientId == null) {
        return "unknown";
    }
    return SecureHash.truncatedSha256(clientId, 16);
}
```

### What a Senior Might Skip

A senior would typically use the raw client IP as the rate limit key and store the token as-is if they use it at all. Token hashing adds CPU cost to every rate-limited request (one SHA-256 computation). The senior might reasonably argue that the rate limit store is internal infrastructure, not a public attack surface. This is true until the Redis instance is misconfigured, the monitoring dashboard exposes keys, or a log aggregator captures them.

The truncation to 16 hex characters (64 bits) is a deliberate balance. The Javadoc on line 26 of `SecureHash.java` notes: "16 hex characters = 64 bits of entropy, sufficient for rate limit keying while keeping cache keys compact." Full SHA-256 (64 hex characters) would be more collision-resistant but would waste storage in Redis for no practical benefit -- 64 bits gives a collision probability of approximately 1 in 2^32 at the scale of a rate limiter.

### Trade-offs

SHA-256 is not a key derivation function. It has no salting and no work factor. An attacker who obtains the truncated hashes and knows the hashing scheme could brute-force short tokens. This is acceptable because: (1) the hashes are for rate limiting, not authentication; (2) tokens in a well-designed system have high entropy (128+ bits); and (3) the purpose is to prevent casual leakage, not to withstand a dedicated cryptanalytic attack.

---

## 2.6 Security Event Dispatching

**File:** `api/src/main/java/aussie/spi/SecurityEvent.java`
**File:** `api/src/main/java/aussie/adapter/out/telemetry/SecurityEventDispatcher.java`
**File:** `api/src/main/java/aussie/spi/SecurityEventHandler.java`
**File:** `api/src/main/java/aussie/adapter/out/telemetry/LoggingSecurityEventHandler.java`
**File:** `api/src/main/java/aussie/adapter/out/telemetry/MetricsSecurityEventHandler.java`

Security events that are not captured in a structured format do not exist for incident response purposes. Grep-ing through unstructured logs for "auth failed" during an active breach is not a strategy.

### What Was Done

`SecurityEvent` is a sealed interface with seven concrete record types:

```java
// SecurityEvent.java, lines 23-24
public sealed interface SecurityEvent {
```

The sealed hierarchy includes: `AuthenticationFailure`, `AuthenticationLockout`, `AccessDenied`, `RateLimitExceeded`, `SuspiciousPattern`, `DosAttackDetected`, and `SessionInvalidated`. Each record carries structured fields and computes its own severity:

```java
// SecurityEvent.java, lines 67-75 (AuthenticationFailure)
record AuthenticationFailure(
        Instant timestamp, String clientIdentifier, String reason,
        String attemptedMethod, int failureCount)
        implements SecurityEvent {

    @Override
    public Severity severity() {
        return failureCount >= 5 ? Severity.WARNING : Severity.INFO;
    }
}
```

```java
// SecurityEvent.java, lines 90-109 (AuthenticationLockout)
record AuthenticationLockout(
        Instant timestamp, String clientIdentifier, String lockedKey,
        int failedAttempts, long lockoutDurationSeconds, int lockoutCount)
        implements SecurityEvent {

    @Override
    public Severity severity() {
        if (lockoutCount >= 3) {
            return Severity.CRITICAL;
        } else if (lockoutCount >= 2 || failedAttempts >= 10) {
            return Severity.WARNING;
        }
        return Severity.INFO;
    }
}
```

Notice that severity is progressive. A first lockout is informational. A third lockout from the same key is critical. This prevents alert fatigue while still escalating persistent attackers.

The `SecurityEventDispatcher` discovers handlers via `ServiceLoader` and dispatches events asynchronously on a dedicated daemon thread:

```java
// SecurityEventDispatcher.java, lines 84-89
executor = Executors.newSingleThreadExecutor(r -> {
    var thread = new Thread(r, "security-event-dispatcher");
    thread.setDaemon(true);
    return thread;
});
```

```java
// SecurityEventDispatcher.java, lines 125-138
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

The `clientIdentifier` field on every event is a hashed IP address, not a raw IP. This is consistent throughout -- the `RateLimitFilter` hashes client IDs before dispatching events (line 301-306), and the `AuthRateLimitFilter` does the same (lines 237-242). Security events that contain raw client data would themselves become a data protection liability.

Two built-in handlers ship with the gateway. The `LoggingSecurityEventHandler` maps severity to log level (INFO events go to DEBUG, WARNING to WARN, CRITICAL to ERROR). The `MetricsSecurityEventHandler` records Micrometer counters tagged by event type, allowing Prometheus/Grafana dashboards to track security events in real time.

The SPI approach (`SecurityEventHandler` interface, `META-INF/services` discovery) means platform teams can plug in their own handlers -- Splunk, PagerDuty, a Kafka topic -- without modifying gateway code.

### What a Senior Might Skip

A senior would log authentication failures and call it done. The pieces that get skipped are: (1) making security events first-class domain objects with a sealed type hierarchy, (2) progressive severity computation, (3) hashing identifiers in events, and (4) the SPI for pluggable handlers. Each of these individually seems like over-engineering. Together, they form the difference between a gateway that can be monitored and one that requires a SIEM team to write custom parsers.

### Trade-offs

The single-threaded executor for event dispatch means that a slow handler (e.g., one that calls an external webhook) blocks all subsequent handlers. This is a deliberate simplicity trade-off: a bounded thread pool would be more robust but introduces backpressure decisions, queue sizing, and dropped event policies. If a handler is slow, the correct fix is to make the handler asynchronous internally, not to add concurrency in the dispatcher.

---

## 2.7 Auth Rate Limiting with Account Lockout

**File:** `api/src/main/java/aussie/system/filter/AuthRateLimitFilter.java`
**File:** `api/src/main/java/aussie/core/service/auth/AuthRateLimitService.java`
**File:** `api/src/main/java/aussie/core/config/AuthRateLimitConfig.java`

HTTP rate limiting (requests per second per client) and authentication rate limiting (failed login attempts per account) are different problems. The first protects server resources. The second protects user accounts. Conflating them means either your API rate limits are too tight (breaking normal usage) or your brute force protection is too loose (allowing credential stuffing).

### What Was Done

The `AuthRateLimitFilter` runs at priority `AUTHENTICATION - 100`, which is before the general `RateLimitFilter` at `AUTHENTICATION - 50`:

```java
// AuthRateLimitFilter.java, line 68
@ServerRequestFilter(priority = Priorities.AUTHENTICATION - 100)
```

This ensures locked-out clients are rejected before they consume HTTP rate limit tokens. The filter only applies to authentication endpoints:

```java
// AuthRateLimitFilter.java, lines 98-103
private boolean isAuthEndpoint(String path) {
    return path.startsWith(AUTH_PATH_PREFIX)
            || path.startsWith(ADMIN_PATH_PREFIX + "/sessions")
            || path.startsWith(ADMIN_PATH_PREFIX + "/api-keys");
}
```

The `AuthRateLimitService` tracks failed attempts by both IP and identifier (username or API key prefix), implementing progressive lockout:

```java
// AuthRateLimitService.java, lines 239-255
private Duration calculateProgressiveLockout(int previousLockouts) {
    if (config.progressiveLockoutMultiplier() <= 1.0) {
        return config.lockoutDuration();
    }

    var multiplier = Math.pow(config.progressiveLockoutMultiplier(), previousLockouts);
    var durationSeconds = (long) (config.lockoutDuration().toSeconds() * multiplier);
    var progressiveDuration = Duration.ofSeconds(durationSeconds);

    if (progressiveDuration.compareTo(config.maxLockoutDuration()) > 0) {
        return config.maxLockoutDuration();
    }

    return progressiveDuration;
}
```

With the default configuration (`AuthRateLimitConfig.java`), the progression is:

| Lockout # | Multiplier | Duration | Computation |
|-----------|-----------|----------|-------------|
| 1st | 1.5^0 = 1.0 | 15 minutes | base |
| 2nd | 1.5^1 = 1.5 | 22.5 minutes | 15 * 1.5 |
| 3rd | 1.5^2 = 2.25 | 33.75 minutes | 15 * 2.25 |
| 4th | 1.5^3 = 3.375 | 50.6 minutes | 15 * 3.375 |
| ... | ... | ... | ... |
| cap | -- | 24 hours | maxLockoutDuration |

The dual tracking (IP + identifier) is important for defending against distributed attacks. An attacker rotating through a botnet of IPs but targeting the same username will trigger the identifier-based lockout even though no single IP exceeds the threshold.

After successful authentication, the service clears failed attempt counters (line 264, `clearFailedAttempts`), so legitimate users are not penalized for past mistakes.

### What a Senior Might Skip

A senior would implement a fixed lockout duration without progressive escalation. The progressive multiplier seems like unnecessary complexity until you observe an actual credential stuffing attack, where the attacker waits out the lockout and retries. Progressive lockout makes each retry more expensive for the attacker while the cap at 24 hours prevents permanent lockout (which becomes a denial-of-service vector against the account owner).

The separation of auth rate limiting from HTTP rate limiting is the more fundamental thing a senior would skip. It requires two separate filter registrations at two different priorities, two different configuration interfaces, and two different storage strategies. But without it, you cannot answer the question "how many failed logins has this account had?" separately from "how many requests has this IP sent?"

### Trade-offs

The `extractClientIp` method in `AuthRateLimitFilter` (lines 154-171) reads `X-Forwarded-For` directly without consulting the `TrustedProxyValidator`. This means the auth rate limiter trusts forwarding headers unconditionally, while the access control layer uses the trusted proxy check. This is a consistency gap -- an attacker behind an untrusted proxy could spoof their IP to evade IP-based auth rate limiting. The identifier-based tracking provides a second line of defense, but the inconsistency is worth noting.

---

## 2.8 CORS at the Vert.x Layer

**File:** `api/src/main/java/aussie/adapter/in/http/CorsFilter.java`

CORS is the security mechanism most frequently implemented incorrectly. The failure mode is silent: the browser enforces CORS, so the developer's local testing (which may not involve cross-origin requests) works fine. The bug surfaces in production when a frontend application on a different domain gets blocked.

### What Was Done

The `CorsFilter` uses Vert.x's `@RouteFilter` at priority 100 rather than a JAX-RS `ContainerResponseFilter`:

```java
// CorsFilter.java, lines 52-53
@RouteFilter(100)
void corsHandler(RoutingContext rc) {
```

Priority 100 is the highest in the filter chain. CORS runs before security headers (priority 90), before WebSocket handling (priority 50), and before any JAX-RS processing.

The filter handles preflight (OPTIONS) requests completely, terminating the request without forwarding it to the application:

```java
// CorsFilter.java, lines 82-86
if ("OPTIONS".equalsIgnoreCase(rc.request().method().name())) {
    LOG.debugf("Handling CORS preflight for origin %s", origin);
    handlePreflight(rc, origin, corsConfig);
    return; // Don't call next() - we're handling the response
}
```

For non-preflight requests, it adds CORS headers and continues the filter chain:

```java
// CorsFilter.java, lines 88-91
addCorsHeaders(rc, origin, corsConfig);
rc.next();
```

The origin validation includes the `*`-with-credentials guard. When `allowCredentials` is true, the filter never returns `*` as the allowed origin -- it echoes the specific origin instead:

```java
// CorsFilter.java, lines 111-116
if (corsConfig.allowedOrigins().contains("*") && !corsConfig.allowCredentials()) {
    rc.response().putHeader(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
} else {
    rc.response().putHeader(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
    rc.response().putHeader(VARY, ORIGIN_HEADER);
}
```

When echoing a specific origin, the `Vary: Origin` header is set. Without it, a shared cache (CDN, reverse proxy) could cache the CORS response for one origin and serve it for another, which would either break legitimate cross-origin requests or weaken the CORS policy.

### Why Not Use Framework CORS Annotations

JAX-RS CORS annotations (e.g., Quarkus's `quarkus.http.cors` configuration) operate at the JAX-RS layer. The Aussie gateway proxies requests that may never reach JAX-RS resource methods -- they are forwarded directly to upstream services. A JAX-RS CORS filter would not apply to these proxied requests. The Vert.x RouteFilter runs at the HTTP routing layer, below JAX-RS, so it intercepts every request regardless of whether it is handled by a JAX-RS resource or forwarded upstream.

### What a Senior Might Skip

A senior would use the framework's built-in CORS support and discover the gap when proxied requests lack CORS headers. The `Vary: Origin` header is the subtler omission -- it is irrelevant in many setups, but causes hard-to-diagnose cache poisoning in setups with shared caching proxies.

### Trade-offs

The CORS configuration is global. Per-service CORS overrides (which the `ServiceRegistration` model supports via its `cors` field) are not evaluated in this filter. The global filter provides a baseline, and per-service CORS would need a separate mechanism that runs after route resolution. This means the global CORS policy must be permissive enough for all services, which may be wider than any individual service needs.

---

## 2.9 Security Headers

**File:** `api/src/main/java/aussie/adapter/in/http/SecurityHeadersFilter.java`
**File:** `api/src/main/java/aussie/adapter/in/http/SecurityHeadersConfig.java`

Security response headers are the cheapest defense in this entire chapter. They cost nothing at runtime, require no state, and prevent entire classes of attacks.

### What Was Done

The `SecurityHeadersFilter` runs at Vert.x RouteFilter priority 90, after CORS but before application logic:

```java
// SecurityHeadersFilter.java, lines 38-39
@RouteFilter(90)
void addSecurityHeaders(RoutingContext rc) {
```

It sets five headers unconditionally and two optionally:

```java
// SecurityHeadersFilter.java, lines 54-61
response.putHeader("X-Content-Type-Options", config.contentTypeOptions());
response.putHeader("X-Frame-Options", config.frameOptions());
response.putHeader("Content-Security-Policy", config.contentSecurityPolicy());
response.putHeader("Referrer-Policy", config.referrerPolicy());
response.putHeader("X-Permitted-Cross-Domain-Policies", config.permittedCrossDomainPolicies());

config.strictTransportSecurity().ifPresent(v -> response.putHeader("Strict-Transport-Security", v));
config.permissionsPolicy().ifPresent(v -> response.putHeader("Permissions-Policy", v));
```

The defaults in `SecurityHeadersConfig` follow OWASP recommendations:

| Header | Default | Purpose |
|--------|---------|---------|
| `X-Content-Type-Options` | `nosniff` | Prevents MIME type sniffing |
| `X-Frame-Options` | `DENY` | Blocks clickjacking via iframes |
| `Content-Security-Policy` | `default-src 'none'` | Blocks all resource loading by default |
| `Referrer-Policy` | `strict-origin-when-cross-origin` | Limits referrer leakage |
| `X-Permitted-Cross-Domain-Policies` | `none` | Blocks Flash/PDF cross-domain access |

### Why HSTS Is Not On by Default

The `Strict-Transport-Security` header is deliberately optional (`Optional<String>`, no default). The Javadoc on lines 74-76 of `SecurityHeadersConfig.java` explains:

```java
// SecurityHeadersConfig.java, lines 74-76
* <p>Not set by default. Only enable when the gateway is behind
* TLS termination. Incorrect HSTS configuration can lock
* browsers out of your site.
```

HSTS tells browsers to refuse non-HTTPS connections for a configurable duration (often one year). If the gateway is not behind TLS termination -- or if TLS termination is later removed -- HSTS makes the gateway unreachable from browsers. Unlike other security headers, HSTS has a destructive failure mode. The gateway cannot know at startup whether it is behind TLS termination, so the safe default is off.

### What a Senior Might Skip

A senior would set `X-Content-Type-Options` and maybe `X-Frame-Options`. The `Content-Security-Policy` default of `default-src 'none'` is aggressive and will break any upstream service that returns HTML with inline scripts or external resources. For an API gateway that primarily serves JSON, this is correct. For a gateway that also serves a developer portal or admin UI, it would need to be relaxed. The `Permissions-Policy` header (controlling browser features like geolocation and camera) is the one most frequently omitted because its absence has no visible effect until an XSS payload leverages those features.

### Trade-offs

All security headers are applied uniformly to all responses. There is no per-service override mechanism. An upstream service that needs a different CSP (e.g., a service that returns HTML dashboards) would need the platform team to either relax the global policy or disable security headers entirely. This is a trade-off between uniformity (easy to audit, hard to misconfigure) and flexibility (each service controls its own headers).

---

## 2.10 Hop-by-Hop Header Stripping

**File:** `api/src/main/java/aussie/core/service/gateway/ProxyRequestPreparer.java`

An API gateway sits at a protocol boundary. HTTP headers that are meaningful between a client and the gateway are not necessarily meaningful -- or safe -- between the gateway and the upstream service.

### What Was Done

The `ProxyRequestPreparer` defines a set of hop-by-hop headers per RFC 2616 Section 13.5.1:

```java
// ProxyRequestPreparer.java, lines 33-41
private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "connection",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade");
```

These headers are stripped from outbound requests during the `copyFilteredHeaders` pass:

```java
// ProxyRequestPreparer.java, lines 91-103
private void copyFilteredHeaders(GatewayRequest request, Map<String, List<String>> headers) {
    for (var entry : request.headers().entrySet()) {
        final var headerName = entry.getKey();
        final var lowerName = headerName.toLowerCase();

        if (shouldSkipHeader(lowerName)) {
            continue;
        }

        headers.put(headerName, List.copyOf(entry.getValue()));
    }
}
```

And from inbound responses via `filterResponseHeaders`:

```java
// ProxyRequestPreparer.java, lines 157-166
public Map<String, List<String>> filterResponseHeaders(Map<String, List<String>> responseHeaders) {
    Map<String, List<String>> filtered = new HashMap<>();
    for (var entry : responseHeaders.entrySet()) {
        var lowerName = entry.getKey().toLowerCase();
        if (!HOP_BY_HOP_HEADERS.contains(lowerName)) {
            filtered.put(entry.getKey(), entry.getValue());
        }
    }
    return filtered;
}
```

The `proxy-authorization` header is the security-critical one. If a client authenticates with the gateway using proxy credentials and those credentials are forwarded to the upstream service, the upstream receives credentials it should never see. The `connection` and `transfer-encoding` headers, while not security-sensitive, can cause protocol errors if forwarded (e.g., a `Connection: keep-alive` header forwarded to an HTTP/2 upstream is a protocol violation).

The `Host` header is also stripped and replaced with the target's host (line 121-128), and `Content-Length` is stripped because the HTTP client will set it correctly based on the actual body size.

### What a Senior Might Skip

A senior would likely forward all headers and strip only the ones that cause visible problems. The `proxy-authorization` header would not be stripped because proxy authentication is not a common pattern in modern architectures. The issue is that the RFC specifies these as hop-by-hop, and compliance-conscious environments (PCI-DSS, SOC 2) will flag unstripped hop-by-hop headers in a security audit. Doing it correctly from the start is cheaper than retrofitting it after an audit finding.

### Trade-offs

The hop-by-hop set is static. RFC 2616 specifies that the `Connection` header can declare additional headers as hop-by-hop (e.g., `Connection: X-Custom-Header`). The current implementation does not parse the `Connection` header to discover these additional headers. This is a known simplification. Implementing dynamic hop-by-hop detection adds complexity and is rarely needed in practice, but it means a client could send `Connection: X-Auth-Token` and the `X-Auth-Token` header would still be forwarded.

---

## Putting It Together: The Filter Chain

Understanding the individual layers is necessary but not sufficient. The order in which they execute determines whether the system is coherent or contradictory. Here is the full chain for an incoming request:

| Priority | Filter | Layer |
|----------|--------|-------|
| 100 | `CorsFilter` | Vert.x RouteFilter |
| 90 | `SecurityHeadersFilter` | Vert.x RouteFilter |
| `AUTH - 100` | `AuthRateLimitFilter` | JAX-RS ServerRequestFilter |
| `AUTH - 50` | `RateLimitFilter` | JAX-RS ServerRequestFilter |
| (default) | `AccessControlFilter` | JAX-RS ServerRequestFilter |
| (application) | `ProxyRequestPreparer` | Business logic |

CORS and security headers run at the Vert.x layer, before JAX-RS even sees the request. This ensures that even 4xx/5xx error responses from the gateway itself carry proper CORS and security headers.

Auth rate limiting runs before HTTP rate limiting. A locked-out client is rejected before it consumes a rate limit token. This prevents a lockout from counting against the legitimate user's rate limit quota when they eventually recover their credentials.

Access control runs after rate limiting. This means a rate-limited client still gets the rate limit response (429) rather than an access denied response (404). The alternative -- checking access first -- would leak information about which paths exist to clients that should receive 404.

Header stripping happens last, during request preparation, because it operates on the outbound request to the upstream, not on the inbound request from the client.

---

## The Pattern

Each layer in this chapter shares a common structure: it assumes the layers around it might fail. The SSRF validator does not trust that the network will block internal addresses. The trusted proxy validator does not trust that forwarding headers are honest. The startup guard does not trust that deployment pipelines will catch misconfiguration. The visibility default does not trust that API teams will remember to configure access. The token hasher does not trust that the rate limit store is secure.

This is not paranoia. It is defense in depth. Each layer is simple, testable, and independently justifiable. The complexity is not in any single layer -- it is in the discipline of building all ten.
