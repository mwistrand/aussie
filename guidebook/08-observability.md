# Chapter 8: Observability -- Telemetry That Supports Operational Decisions

Observability is the feature that makes every other feature debuggable. When a rate limit misfires at 2 AM, you need traces that show which sampling rate was applied, which service was targeted, and whether the decision was correct. When a team disputes their infrastructure bill, you need metrics that attribute every request to the right cost center. When a credential-stuffing attack hits the gateway, you need security events that reach your SIEM within seconds.

This chapter covers how the Aussie gateway builds observability that serves operators, not just developers. Every decision here -- from how span attributes are gated to why rate-limited requests get `StatusCode.OK` -- reflects a production environment where telemetry volume is a cost center and alert fatigue kills incident response.

---

## 8.1 Structured Telemetry with OpenTelemetry

### The Implementation

Aussie integrates with OpenTelemetry through Quarkus's OTel extension and adds gateway-specific span attributes through two key classes: `SpanAttributes`, which defines the attribute vocabulary, and `TelemetryHelper`, which conditionally applies attributes based on configuration.

The attribute vocabulary lives in a constants class with clear semantic groupings:

```java
// api/src/main/java/aussie/adapter/out/telemetry/SpanAttributes.java (lines 16-235)

public final class SpanAttributes {

    private SpanAttributes() {}

    // OpenTelemetry Semantic Convention Attributes
    public static final String HTTP_METHOD = "http.method";
    public static final String HTTP_URL = "http.url";
    public static final String HTTP_STATUS_CODE = "http.status_code";
    public static final String NET_PEER_NAME = "net.peer.name";
    public static final String NET_PEER_PORT = "net.peer.port";

    // Service Identification
    public static final String SERVICE_ID = "aussie.service.id";
    public static final String SERVICE_NAME = "aussie.service.name";
    public static final String ROUTE_PATTERN = "aussie.route.pattern";
    public static final String ROUTE_PATH = "aussie.route.path";

    // Proxy/Upstream Attributes
    public static final String UPSTREAM_HOST = "aussie.upstream.host";
    public static final String UPSTREAM_PORT = "aussie.upstream.port";
    public static final String UPSTREAM_URI = "aussie.upstream.uri";
    public static final String UPSTREAM_LATENCY_MS = "aussie.upstream.latency_ms";

    // Traffic Attribution
    public static final String TEAM_ID = "aussie.team.id";
    public static final String TENANT_ID = "aussie.tenant.id";
    public static final String CLIENT_APPLICATION = "aussie.client.application";

    // Rate Limiting Attributes
    public static final String RATE_LIMITED = "aussie.rate_limit.limited";
    public static final String RATE_LIMIT_REMAINING = "aussie.rate_limit.remaining";
    public static final String RATE_LIMIT_RETRY_AFTER = "aussie.rate_limit.retry_after";
    public static final String RATE_LIMIT_TYPE = "aussie.rate_limit.type";

    // ...
}
```

Notice the naming convention: standard OTel semantic conventions (`http.method`, `net.peer.name`) sit alongside gateway-specific attributes under the `aussie.*` namespace. This is deliberate. Standard attributes enable generic dashboards and cross-service correlation. Gateway-specific attributes provide the operational context you need to debug gateway behavior without conflating it with general HTTP semantics.

The `TelemetryHelper` class gates every attribute behind both a master telemetry switch and a per-attribute feature flag:

```java
// api/src/main/java/aussie/adapter/out/telemetry/TelemetryHelper.java (lines 17-132)

@ApplicationScoped
public class TelemetryHelper {

    private final TelemetryConfig config;

    @Inject
    public TelemetryHelper(TelemetryConfig config) {
        this.config = config;
    }

    private boolean isTracingEnabled() {
        return config.enabled() && config.tracing().enabled();
    }

    public void setUpstreamUri(Span span, String value) {
        if (isTracingEnabled() && attrs().upstreamUri()) {
            span.setAttribute(SpanAttributes.UPSTREAM_URI, value);
        }
    }

    public void setRateLimited(Span span, boolean value) {
        if (isTracingEnabled() && attrs().rateLimited()) {
            span.setAttribute(SpanAttributes.RATE_LIMITED, value);
        }
    }

    public void setRateLimitRemaining(Span span, long value) {
        if (isTracingEnabled() && attrs().rateLimitRemaining()) {
            span.setAttribute(SpanAttributes.RATE_LIMIT_REMAINING, value);
        }
    }

    // ...
}
```

Every setter follows the same pattern: check the master switch, check the attribute-specific toggle, then set the attribute. This is boilerplate, and that is the point. The pattern is so simple that any engineer can add a new attribute in under a minute, and the configuration surface is immediately visible in the `TelemetryConfig.AttributesConfig` interface.

### W3C Trace Context Propagation Through the Proxy

The gateway sits between clients and upstream services, which means it must propagate trace context faithfully. The `ProxyHttpClient` handles this by injecting the current OTel context into outgoing requests using the standard `TextMapPropagator`:

```java
// api/src/main/java/aussie/adapter/out/http/ProxyHttpClient.java (lines 49-132)

private static final TextMapSetter<HttpRequest<Buffer>> HEADER_SETTER =
        (carrier, key, value) -> carrier.putHeader(key, value);

// In the forward() method:
var span = tracer.spanBuilder("HTTP " + preparedRequest.method())
        .setSpanKind(SpanKind.CLIENT)
        .setAttribute(SpanAttributes.HTTP_METHOD, preparedRequest.method())
        .setAttribute(SpanAttributes.HTTP_URL, targetUri.toString())
        .setAttribute(SpanAttributes.NET_PEER_NAME, targetUri.getHost())
        .setAttribute(SpanAttributes.NET_PEER_PORT, (long) getPort(targetUri))
        .startSpan();

// Add configurable upstream attributes
telemetryHelper.setUpstreamHost(span, targetUri.getHost());
telemetryHelper.setUpstreamPort(span, getPort(targetUri));
telemetryHelper.setUpstreamUri(span, targetUri.toString());

var request = createRequest(method, targetUri);
applyHeaders(preparedRequest, request);

// Propagate trace context (W3C Trace Context headers)
propagator.inject(Context.current().with(span), request, HEADER_SETTER);
```

The `HEADER_SETTER` lambda adapts between OTel's `TextMapSetter` interface and Vert.x's `HttpRequest` API. The propagator injects `traceparent` and `tracestate` headers into the outgoing request, enabling end-to-end distributed tracing from client through gateway to upstream service.

The corresponding Quarkus configuration enables this:

```properties
quarkus.otel.propagators=tracecontext,baggage
```

### Why This Pattern and Not Something Simpler

A senior engineer might reach for one of several simpler approaches:

**Approach 1: Set all attributes unconditionally.** This is fine for a small-scale gateway, but in production, each span attribute has a storage cost in your tracing backend. The `upstream-uri` attribute, for instance, is disabled by default (`@WithDefault("false")` in `TelemetryConfig.AttributesConfig`, line 215) because it includes query parameters, creating unbounded cardinality. At scale, unbounded cardinality can crash or significantly degrade your tracing backend.

**Approach 2: Use a single attribute allowlist/blocklist instead of per-attribute flags.** This is more concise but less discoverable. With per-attribute flags, every configurable dimension appears in the `TelemetryConfig.AttributesConfig` interface with its default value and a Javadoc comment. An operator can see exactly what they are controlling without reading code.

**Approach 3: Use OTel's SpanProcessor to filter attributes centrally.** This is cleaner from an OTel purist perspective, but it moves the cardinality control to a post-hoc filter rather than preventing the attribute from ever being computed. For attributes that require computation (like hashing client identifiers), preventing creation is more efficient than creating and discarding.

### Trade-offs

The `TelemetryHelper` wrapper adds indirection. Every span attribute write goes through an `if` check and a method call. In practice, this overhead is negligible compared to the cost of serializing and exporting the span. The real cost is cognitive: new engineers must learn to use `TelemetryHelper` rather than calling `span.setAttribute` directly. This is enforced by convention, not by the compiler.

The per-attribute configuration creates a large configuration surface. With 14 configurable attributes in `TelemetryConfig.AttributesConfig` (lines 188-257), the configuration file can grow. In practice, most deployments use the defaults and override one or two attributes. The alternative -- a smaller configuration surface -- would require operators to fork the code to change cardinality behavior.

---

## 8.2 Metrics as Configuration Gauges

### The Implementation

`BulkheadMetrics` exposes the *configured limits* of connection pools as Micrometer gauges, not the runtime utilization. This is a deliberate choice.

```java
// api/src/main/java/aussie/adapter/out/telemetry/BulkheadMetrics.java (lines 43-132)

@ApplicationScoped
public class BulkheadMetrics implements MeterBinder {

    private static final Logger LOG = Logger.getLogger(BulkheadMetrics.class);

    private final TelemetryConfig telemetryConfig;
    private final ResiliencyConfig resiliencyConfig;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    @Inject
    public BulkheadMetrics(TelemetryConfig telemetryConfig, ResiliencyConfig resiliencyConfig) {
        this.telemetryConfig = telemetryConfig;
        this.resiliencyConfig = resiliencyConfig;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (!isEnabled()) {
            LOG.debug("Bulkhead metrics disabled (telemetry not enabled)");
            return;
        }

        if (!registered.compareAndSet(false, true)) {
            LOG.debug("Bulkhead metrics already registered, skipping");
            return;
        }

        final int cassandraPoolSize = resiliencyConfig.cassandra().poolLocalSize();
        final int cassandraMaxRequests = resiliencyConfig.cassandra().maxRequestsPerConnection();
        final int redisPoolSize = resiliencyConfig.redis().poolSize();
        final int httpMaxPerHost = resiliencyConfig.http().maxConnectionsPerHost();
        final int httpMaxTotal = resiliencyConfig.http().maxConnections();
        final int jwksMax = resiliencyConfig.jwks().maxConnections();

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

        Gauge.builder("aussie.bulkhead.http.pool.max.total", () -> httpMaxTotal)
                .description("Maximum total HTTP connections across all upstream hosts")
                .tag("type", "connection_pool")
                .register(registry);

        // ...
    }
}
```

### Why Configuration, Not Usage

This trips people up. Why expose the *configured maximum* as a gauge rather than the *current utilization*? Two reasons.

**Config drift is systemic, pool usage is transient.** If a deployment has Cassandra pool max at 30 on three instances and 10 on a fourth (because someone tested a config change and forgot to revert), that is a latent failure. Under normal load, you will never notice. Under a traffic spike, the under-provisioned instance becomes the bottleneck. Exposing the configuration as a metric lets you write alerts that detect drift:

```yaml
# From docs/platform/observability.md (lines 396-402)
- alert: CassandraPoolNearCapacity
  expr: |
    cassandra_pool_open_connections / on() aussie_bulkhead_cassandra_pool_max > 0.85
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Cassandra connection pool at >85% capacity"
```

This PromQL divides the *actual pool usage* (from the Cassandra driver's own metrics) by the *configured limit* (from `BulkheadMetrics`). The configured limit is the denominator. Without it, you would need to hardcode the expected pool size in your alert rule, creating a second source of truth that drifts from configuration.

**Pool usage metrics already exist.** As the class Javadoc notes (lines 33-38), actual pool utilization is provided by the respective drivers: Cassandra via `quarkus.cassandra.metrics.enabled=true`, Redis via Quarkus Redis extension metrics, and HTTP via Vert.x metrics. Duplicating those metrics would be wasteful and potentially inconsistent. Instead, `BulkheadMetrics` provides the missing piece: the configuration side of the equation.

### What a Senior Might Do Instead

A common alternative is to instrument the pool wrappers directly and expose both the limit and the current utilization from a single metrics class. This works but creates a coupling between the metrics layer and the driver layer. If you swap Redis implementations (say, from Quarkus Redis to Jedis), you need to rewrite the metrics class. The current approach treats the configuration as a stable fact and delegates usage tracking to whatever driver is doing the actual pooling.

Another alternative is to skip bulkhead metrics entirely and just monitor error rates. This works in practice but provides no early warning. By the time you see errors from pool exhaustion, the damage is done.

### The AtomicBoolean Guard

The `registered.compareAndSet(false, true)` guard on line 73 prevents double-registration if `bindTo` is called multiple times. Micrometer's `MeterRegistry` will throw on duplicate metric names, and in Quarkus lifecycle events can occasionally fire in unexpected orders. The guard makes this code idempotent.

---

## 8.3 Security Event Dispatching

### The Implementation

The security event system has three layers: the event types (a `sealed interface` hierarchy), the dispatcher (which manages async delivery), and the handler SPI (which allows extensible processing).

The event types are defined as a sealed interface with seven permitted record implementations:

```java
// api/src/main/java/aussie/spi/SecurityEvent.java (lines 23-220)

public sealed interface SecurityEvent {

    Instant timestamp();
    String clientIdentifier();
    Severity severity();

    enum Severity { INFO, WARNING, CRITICAL }

    record AuthenticationFailure(
            Instant timestamp, String clientIdentifier, String reason,
            String attemptedMethod, int failureCount)
            implements SecurityEvent {
        @Override
        public Severity severity() {
            return failureCount >= 5 ? Severity.WARNING : Severity.INFO;
        }
    }

    record RateLimitExceeded(
            Instant timestamp, String clientIdentifier, String serviceId,
            int requestCount, int threshold, int windowSeconds)
            implements SecurityEvent {
        @Override
        public Severity severity() {
            double ratio = (double) requestCount / threshold;
            if (ratio > 5.0) return Severity.CRITICAL;
            else if (ratio > 2.0) return Severity.WARNING;
            return Severity.INFO;
        }
    }

    record DosAttackDetected(
            Instant timestamp, String clientIdentifier, String attackType,
            Map<String, Object> evidence)
            implements SecurityEvent {
        @Override
        public Severity severity() {
            return Severity.CRITICAL;
        }
    }

    // Also: AuthenticationLockout, AccessDenied, SuspiciousPattern, SessionInvalidated
}
```

Three design decisions stand out here.

First, **sealed interface with records.** Because the interface is sealed, the `MetricsSecurityEventHandler` (line 76-84) can use exhaustive pattern matching in a `switch` expression without a default branch. The compiler enforces that every event type is handled. When you add a new event type, every handler gets a compile error until it is updated. This is safety that Java's type system provides for free.

Second, **severity is computed, not assigned.** `RateLimitExceeded` computes severity based on the ratio of actual requests to the threshold. A client at 1.5x the limit is INFO; at 3x it is WARNING; at 6x it is CRITICAL. `AuthenticationFailure` escalates severity when the failure count reaches 5. This means severity is determined by the event data itself, not by the code that creates the event. You get consistent severity classification regardless of which filter or handler produces the event.

Third, **client identifiers are hashed.** The `clientIdentifier` field contains a hashed IP, not the raw IP. This is visible in the `RateLimitFilter` where the dispatch call hashes the client ID before creating the event (line 301-305):

```java
// api/src/main/java/aussie/system/filter/RateLimitFilter.java (lines 301-306)

private String hashClientId(String clientId) {
    if (clientId == null) {
        return "unknown";
    }
    return SecureHash.truncatedSha256(clientId, 16);
}
```

### Async Dispatch via Single-Threaded Executor

The `SecurityEventDispatcher` uses a single-threaded executor with daemon threads to dispatch events asynchronously:

```java
// api/src/main/java/aussie/adapter/out/telemetry/SecurityEventDispatcher.java (lines 48-89)

@PostConstruct
void init() {
    if (!enabled) {
        LOG.debug("Security monitoring is disabled - event dispatcher inactive");
        return;
    }

    // Load handlers via ServiceLoader
    var loadedHandlers = ServiceLoader.load(SecurityEventHandler.class).stream()
            .map(ServiceLoader.Provider::get)
            .toList();

    // Filter available and sort by priority
    handlers = loadedHandlers.stream()
            .filter(SecurityEventHandler::isAvailable)
            .sorted(Comparator.comparingInt(SecurityEventHandler::priority).reversed())
            .toList();

    // Create executor for async dispatch
    executor = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r, "security-event-dispatcher");
        thread.setDaemon(true);
        return thread;
    });
}
```

The dispatch method itself is intentionally simple:

```java
// api/src/main/java/aussie/adapter/out/telemetry/SecurityEventDispatcher.java (lines 125-139)

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

A single-threaded executor is chosen because security events must be processed in order for correct correlation (a series of `AuthenticationFailure` events leading to an `AuthenticationLockout` should arrive at the SIEM in chronological order), and because throughput is not the concern -- the rate of security events is orders of magnitude lower than the rate of requests.

### The Handler SPI

Handlers are discovered via `ServiceLoader` and implement the `SecurityEventHandler` interface:

```java
// api/src/main/java/aussie/spi/SecurityEventHandler.java (lines 37-107)

public interface SecurityEventHandler {

    String name();

    default int priority() { return 0; }

    default boolean isAvailable() { return true; }

    void handle(SecurityEvent event);

    default void close() {}
}
```

Two built-in handlers ship with the gateway:

- `LoggingSecurityEventHandler` (priority 0): Maps event severity to log levels (INFO -> DEBUG, WARNING -> WARN, CRITICAL -> ERROR). Formats events as structured key-value pairs for log aggregation tools.
- `MetricsSecurityEventHandler` (priority 10): Records events as Micrometer counters with dimensional tags. Uses exhaustive pattern matching to record type-specific metrics like `aussie.security.auth.failures` and `aussie.security.dos.detected`.

Custom handlers are registered via `META-INF/services/aussie.spi.SecurityEventHandler` and can integrate with Slack, PagerDuty, or any SIEM. The `isAvailable()` method lets handlers gracefully degrade when their external dependencies (webhook URLs, API keys) are not configured.

### Why ServiceLoader and Not CDI

A senior engineer working in a CDI-heavy framework like Quarkus might ask: why use `ServiceLoader` for handler discovery instead of CDI `Instance<SecurityEventHandler>`?

The answer is deployment flexibility. `ServiceLoader` allows handlers to live in separate JARs that are dropped onto the classpath without modifying the gateway's CDI configuration. A platform team can ship a custom SIEM handler as an independent artifact. CDI would require the handler to be a CDI bean, which means it must be in a bean archive (a JAR with `beans.xml` or annotated appropriately), and its dependencies must be available in the CDI container. `ServiceLoader` imposes none of these constraints.

The trade-off is that `ServiceLoader`-discovered handlers cannot use CDI injection. The `MetricsSecurityEventHandler` works around this by accepting a `MeterRegistry` via a setter method that the dispatcher calls after discovery (line 61-65 of `SecurityEventDispatcher.java`). This is slightly ugly but avoids coupling the SPI to a specific DI framework.

---

## 8.4 RFC 9457 Problem Details

### The Implementation

`GatewayProblem` provides factory methods that create RFC 9457 structured error responses using the `quarkus-resteasy-problem` library:

```java
// api/src/main/java/aussie/adapter/in/problem/GatewayProblem.java (lines 17-191)

public final class GatewayProblem {

    private GatewayProblem() {}

    public static HttpProblem serviceNotFound(String serviceId) {
        return HttpProblem.builder()
                .withTitle("Service Not Found")
                .withStatus(Status.NOT_FOUND)
                .withDetail("Service '%s' is not registered".formatted(serviceId))
                .build();
    }

    public static HttpProblem tooManyRequests(
            String detail, long retryAfterSeconds, long limit,
            long remaining, long resetAt) {
        return HttpProblem.builder()
                .withTitle("Too Many Requests")
                .withStatus(Status.fromStatusCode(429))
                .withDetail(detail)
                .with("retryAfter", retryAfterSeconds)
                .with("limit", limit)
                .with("remaining", remaining)
                .with("resetAt", resetAt)
                .build();
    }

    public static HttpProblem badGateway(String detail) {
        return HttpProblem.builder()
                .withTitle("Bad Gateway")
                .withStatus(Status.BAD_GATEWAY)
                .withDetail(detail)
                .build();
    }

    // Also: routeNotFound, badRequest, validationError, unauthorized,
    //       forbidden, payloadTooLarge, headerTooLarge, conflict,
    //       internalError, featureDisabled
}
```

This produces JSON responses like:

```json
{
  "type": "about:blank",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Rate limit exceeded. Retry after 30 seconds.",
  "retryAfter": 30,
  "limit": 100,
  "remaining": 0,
  "resetAt": 1707408000
}
```

### Why Structured Errors Matter

Structured error responses serve two audiences.

**API consumers** get machine-parseable errors. A client can check `status == 429`, read `retryAfter`, and implement exponential backoff without parsing error message strings. The `tooManyRequests` factory method (lines 115-126) enriches the standard RFC 9457 fields with rate-limit-specific extension members (`retryAfter`, `limit`, `remaining`, `resetAt`). A client SDK can map these directly to retry logic.

**Operations teams** get consistent error taxonomy. When every error goes through `GatewayProblem`, the `title` field is always one of a known set of values. You can write log aggregation queries like `title:"Bad Gateway"` and get every upstream failure, regardless of which service produced it. Without this, error messages vary by code path and become unfilterable at scale.

### Design Decision: Utility Class with Static Factories

`GatewayProblem` is a utility class, not a CDI bean. This is intentional. Error construction has no dependencies -- it simply builds immutable `HttpProblem` instances. Making it a CDI bean would add injection overhead with no benefit. The static factory pattern also makes the error catalog self-documenting: open `GatewayProblem.java` and you see every error the gateway can produce.

A senior engineer might argue for an enum-based error catalog with error codes. That approach works but requires maintaining a parallel code registry and mapping between codes and HTTP statuses. The factory method approach keeps the error definition close to the HTTP semantics. The trade-off is that error types are not enumerable at compile time -- you discover the full error catalog by reading the class, not by iterating an enum.

---

## 8.5 Traffic Attribution for Cost Allocation

### The Implementation

Traffic attribution tracks which teams and tenants are consuming gateway capacity. It consists of three classes: the `TrafficAttribution` record (which captures attribution dimensions), the `TrafficAttributionService` (which records metrics), and the `TrafficAttributing` port interface (which defines the contract).

The attribution record extracts dimensions from request headers and service registration:

```java
// api/src/main/java/aussie/adapter/out/telemetry/TrafficAttribution.java (lines 18-83)

public record TrafficAttribution(
        String serviceId, String teamId, String tenantId,
        String clientApplication, String environment) {

    public static TrafficAttribution from(
            GatewayRequest request, ServiceRegistration service,
            TelemetryConfig.AttributionConfig config) {

        return new TrafficAttribution(
                service.serviceId(),
                getHeader(request, config.teamHeader()),
                getHeader(request, config.tenantHeader()),
                getHeader(request, config.clientAppHeader()),
                System.getenv("AUSSIE_ENV"));
    }
}
```

The header names are configurable, defaulting to `X-Team-ID`, `X-Tenant-ID`, and `X-Client-Application`. The environment comes from an environment variable because it is a deployment-time constant, not a per-request value.

The service records five metric families, all tagged with the same attribution dimensions:

```java
// api/src/main/java/aussie/adapter/out/telemetry/TrafficAttributionService.java (lines 106-148)

public void recordAttributedRequest(TrafficAttribution attribution, RequestMetrics metrics) {
    if (!enabled) { return; }

    var tags = buildTags(attribution);

    // Request count
    Counter.builder("aussie.attributed.requests.total")
            .tags(tags).register(registry).increment();

    // Data transfer
    Counter.builder("aussie.attributed.bytes.ingress")
            .tags(tags).register(registry).increment(metrics.requestBytes());

    Counter.builder("aussie.attributed.bytes.egress")
            .tags(tags).register(registry).increment(metrics.responseBytes());

    // Compute units (normalized cost)
    var computeUnits = calculateComputeUnits(metrics);
    Counter.builder("aussie.attributed.compute.units")
            .tags(tags).register(registry).increment(computeUnits);

    // Request duration
    Timer.builder("aussie.attributed.duration")
            .tags(tags).publishPercentiles(0.5, 0.9, 0.99)
            .register(registry).record(metrics.durationMs(), TimeUnit.MILLISECONDS);
}
```

The tag set is intentionally small and bounded:

```java
// api/src/main/java/aussie/adapter/out/telemetry/TrafficAttributionService.java (lines 150-156)

private Tags buildTags(TrafficAttribution attribution) {
    return Tags.of(
            "service_id", attribution.serviceIdOrUnknown(),
            "team_id", attribution.teamIdOrUnknown(),
            "tenant_id", attribution.tenantIdOrUnknown(),
            "environment", attribution.environmentOrUnknown());
}
```

The `*OrUnknown()` methods on `TrafficAttribution` ensure that missing dimensions are tagged as `"unknown"` rather than `null`, preventing Micrometer exceptions from null tag values and keeping the cardinality bounded.

### Compute Units

The compute unit calculation normalizes heterogeneous resource consumption into a single scalar:

```java
// api/src/main/java/aussie/adapter/out/telemetry/TrafficAttributionService.java (lines 171-182)

private double calculateComputeUnits(RequestMetrics metrics) {
    double baseCost = 1.0;
    double dataCost = (metrics.requestBytes() + metrics.responseBytes()) / 10_000.0;
    double timeCost = metrics.durationMs() / 100.0;
    return baseCost + dataCost + timeCost;
}
```

Every request costs at least 1.0 compute units. Each 10 KB of data transfer adds 1.0 units. Each 100 ms of processing time adds 1.0 units. This is a simple linear model that makes cost conversations tractable without requiring a PhD in infrastructure economics. The specific weights (10 KB, 100 ms) are hardcoded, which makes them easy to understand but hard to customize. If your organization needs different cost weights, this is the function to change.

### PromQL for Billing Dashboards

The platform observability documentation provides PromQL examples for common billing queries:

```promql
# Requests by team (from docs/platform/observability.md, lines 342-343)
sum(rate(aussie_attributed_requests_total[5m])) by (team_id)

# Data transfer by tenant (lines 346-347)
sum(rate(aussie_attributed_bytes_ingress[5m])
  + rate(aussie_attributed_bytes_egress[5m])) by (tenant_id)

# Compute units by service (lines 350-351)
sum(rate(aussie_attributed_compute_units[5m])) by (service_id)
```

These queries work because the tag dimensions are consistent across all five metric families. You can slice by any combination of `service_id`, `team_id`, `tenant_id`, and `environment`.

### Trade-offs

Traffic attribution adds five metric recordings per request. For a gateway handling 10,000 requests per second, that is 50,000 counter increments per second. Micrometer counters are thread-safe atomic operations, so this is fast, but it is not free. The `enabled` flag (checked at the top of every `record` call) ensures zero overhead when attribution is disabled.

The header-based attribution model assumes that clients are trustworthy about their identity. A malicious client could set `X-Team-ID: someone-else` and shift costs to another team. In environments where this is a concern, attribution should be derived from authenticated identity (API key or JWT claims) rather than request headers. The current design trades security for simplicity and configurability.

---

## 8.6 Rate Limit Span Semantics

### The Implementation

When a request is rate-limited, the span status is set to `StatusCode.OK`, not `StatusCode.ERROR`:

```java
// api/src/main/java/aussie/system/filter/RateLimitFilter.java (lines 170-176)

private void setExceededSpanAttributes(RateLimitDecision decision) {
    final var span = Span.current();
    telemetryHelper.setRateLimitRetryAfter(span, decision.retryAfterSeconds());
    // Use OK status - rate limiting is expected behavior, not an error
    // Errors would trigger alerts; rate limits are informational
    span.setStatus(StatusCode.OK, "Rate limit exceeded");
}
```

The same pattern appears in the WebSocket rate limit filter:

```java
// api/src/main/java/aussie/adapter/in/websocket/WebSocketRateLimitFilter.java (line 161)

span.setStatus(StatusCode.OK, "WebSocket connection rate limit exceeded");
```

### Why This Is Correct

This is one of those decisions that looks wrong to anyone who has not operated a high-traffic gateway.

In OpenTelemetry, `StatusCode.ERROR` signals that something went wrong -- the operation failed due to an unexpected condition. Tracing backends like Jaeger and Datadog use span status to compute error rates and trigger alerts. If rate-limited requests are marked as errors, then a popular service experiencing healthy rate limiting looks like it is failing. Error rate dashboards become useless because they mix genuine failures (upstream timeouts, authentication errors) with expected traffic management.

Rate limiting is not a failure. It is the system working correctly. A 429 response means the gateway successfully enforced a policy. The span attributes (`rate_limit.limited`, `rate_limit.remaining`, `rate_limit.retry_after`) provide full observability into what happened. The `OK` status ensures that error-based alerting remains meaningful.

The `RateLimitFilter` does not ignore rate limits in telemetry -- it dispatches a `SecurityEvent.RateLimitExceeded` for SIEM integration and records metrics via `metrics.recordRateLimitExceeded`. The distinction is between *error signals* (which trigger automated responses) and *observability signals* (which inform humans and dashboards).

### What a Senior Might Do Instead

A common alternative is to use `StatusCode.UNSET` (the default) instead of `StatusCode.OK`. This avoids making a positive assertion about the operation's success. The risk is that some tracing backends treat `UNSET` spans differently from `OK` spans in their error rate calculations. `StatusCode.OK` is an explicit statement: this span completed as intended.

Another approach is to use `StatusCode.ERROR` and then filter rate-limit errors from alert rules. This works but pushes complexity to every consumer of the trace data. Every team writing alert rules, building dashboards, or investigating incidents must remember to exclude rate limits from their error queries. The span status approach handles this once, at the source.

---

## 8.7 Hierarchical Sampling

### The Problem

A flat sampling rate does not work for an API gateway. Some services (payment processing) need 100% trace coverage. Others (health checks, static assets) need almost none. A gateway-wide 10% sampling rate either over-samples the high-volume services (expensive) or under-samples the critical ones (dangerous).

### The Implementation

The hierarchical sampling system has four components: the `SamplingConfig` (platform-wide configuration), the `HierarchicalSampler` (OTel Sampler implementation), the `SamplingResolver` (rate lookup with caching), and the `SamplingResolverHolder` (bridge between OTel SPI and CDI lifecycles).

The config defines platform defaults and bounds:

```java
// api/src/main/java/aussie/core/config/SamplingConfig.java (lines 34-149)

@ConfigMapping(prefix = "aussie.telemetry.sampling")
public interface SamplingConfig {

    @WithDefault("false")
    boolean enabled();

    @WithName("default-rate")
    @WithDefault("1.0")
    double defaultRate();

    @WithName("minimum-rate")
    @WithDefault("0.0")
    double minimumRate();

    @WithName("maximum-rate")
    @WithDefault("1.0")
    double maximumRate();

    CacheConfig cache();
    LookupConfig lookup();

    interface CacheConfig {
        @WithDefault("true")
        boolean redisEnabled();

        @WithName("redis-ttl")
        @WithDefault("PT5M")
        java.time.Duration redisTtl();
    }
}
```

The `minimumRate` and `maximumRate` act as platform guardrails. A service team cannot set their sampling rate to 0.0 if the platform minimum is 0.01 (1%). This prevents critical traces from being completely suppressed.

The `HierarchicalSampler` implements the OTel `Sampler` interface:

```java
// api/src/main/java/aussie/adapter/out/telemetry/HierarchicalSampler.java (lines 43-184)

public class HierarchicalSampler implements Sampler {

    private final Sampler fallbackSampler;
    private final double fallbackRate;

    public HierarchicalSampler(double fallbackRate) {
        this.fallbackRate = fallbackRate;
        this.fallbackSampler = Sampler.traceIdRatioBased(fallbackRate);
    }

    @Override
    public SamplingResult shouldSample(
            Context parentContext, String traceId, String name,
            SpanKind spanKind, Attributes attributes, List<LinkData> parentLinks) {

        final var resolverOpt = SamplingResolverHolder.get();
        if (resolverOpt.isEmpty()) {
            return fallbackSampler.shouldSample(
                    parentContext, traceId, name, spanKind, attributes, parentLinks);
        }

        final var resolver = resolverOpt.get();
        if (!resolver.isEnabled()) {
            return fallbackSampler.shouldSample(
                    parentContext, traceId, name, spanKind, attributes, parentLinks);
        }

        final var serviceId = extractServiceId(name, attributes);
        final var effectiveRate = resolver.resolveByServiceIdNonBlocking(serviceId);

        if (ThreadLocalRandom.current().nextDouble() < effectiveRate.rate()) {
            return SamplingResult.recordAndSample();
        }

        return SamplingResult.drop();
    }
}
```

Several critical design decisions are embedded in this code.

### The SamplingResolverHolder Bridge

OTel Samplers are instantiated by the OTel SPI before CDI is fully initialized. The `HierarchicalSampler` cannot inject CDI beans. The `SamplingResolverHolder` solves this with a static volatile field that the `SamplingResolverInitializer` populates during Quarkus startup:

```java
// api/src/main/java/aussie/adapter/out/telemetry/SamplingResolverHolder.java (lines 16-52)

public final class SamplingResolverHolder {

    private static volatile SamplingResolver instance;

    public static void set(SamplingResolver resolver) {
        instance = resolver;
    }

    public static Optional<SamplingResolver> get() {
        return Optional.ofNullable(instance);
    }

    public static void clear() {
        instance = null;
    }
}
```

This is a necessary evil. Static mutable state is generally a code smell, but the OTel SPI lifecycle demands it. The `volatile` keyword ensures visibility across threads. The `Optional` return from `get()` forces callers to handle the "not yet initialized" case, and the `HierarchicalSampler` gracefully falls back to the default rate.

### Non-Blocking Resolution

The `resolveByServiceIdNonBlocking` method in `SamplingResolver` (lines 129-144) returns the platform default immediately on cache miss and populates the cache asynchronously:

```java
// api/src/main/java/aussie/adapter/out/telemetry/SamplingResolver.java (lines 129-144)

public EffectiveSamplingRate resolveByServiceIdNonBlocking(String serviceId) {
    if (serviceId == null || "unknown".equals(serviceId)) {
        return getCachedPlatformDefault();
    }

    final var cachedConfig = localCache.get(serviceId);
    if (cachedConfig.isPresent()) {
        return resolveRate(Optional.empty(), cachedConfig.get());
    }

    // Cache miss - return platform default immediately, populate async
    populateCacheAsync(serviceId);
    platformDefaultFallbacks.increment();
    return getCachedPlatformDefault();
}
```

This is the key trade-off: the first request to a new service uses the platform default rate, not the service-specific rate. Subsequent requests (within the cache TTL) use the correct rate. For a gateway handling millions of requests, the first request being sampled at the wrong rate is insignificant. Blocking on a Redis or Cassandra lookup inside the sampler path would add latency to every request.

### Resolution Hierarchy

The `SamplingResolver` applies a three-level hierarchy with platform bounds clamping:

```java
// api/src/main/java/aussie/adapter/out/telemetry/SamplingResolver.java (lines 323-340)

private EffectiveSamplingRate resolveRate(
        Optional<EndpointSamplingConfig> endpointConfig,
        Optional<ServiceSamplingConfig> serviceConfig) {

    // Endpoint-level has highest priority
    if (endpointConfig.isPresent() && endpointConfig.get().samplingRate().isPresent()) {
        return new EffectiveSamplingRate(
                endpointConfig.get().samplingRate().get(), SamplingSource.ENDPOINT)
                .clampToPlatformBounds(config.minimumRate(), config.maximumRate());
    }

    // Service-level has second priority
    if (serviceConfig.isPresent() && serviceConfig.get().samplingRate().isPresent()) {
        return new EffectiveSamplingRate(
                serviceConfig.get().samplingRate().get(), SamplingSource.SERVICE)
                .clampToPlatformBounds(config.minimumRate(), config.maximumRate());
    }

    return getCachedPlatformDefault();
}
```

The `EffectiveSamplingRate` record carries the source (`PLATFORM`, `SERVICE`, or `ENDPOINT`) alongside the rate, which is invaluable for debugging why a particular service is being sampled at a particular rate.

### Multi-Instance Consistency via Redis Cache

The `SamplingConfig.CacheConfig` enables Redis caching for sampling configurations:

```java
// api/src/main/java/aussie/core/config/SamplingConfig.java (lines 102-127)

interface CacheConfig {
    @WithDefault("true")
    boolean redisEnabled();

    @WithName("redis-ttl")
    @WithDefault("PT5M")
    java.time.Duration redisTtl();
}
```

When multiple gateway instances are running, a sampling rate change needs to propagate to all instances. The local in-memory cache uses a shorter TTL (configured via `LocalCacheConfig`), while the Redis cache uses a 5-minute TTL. When a local cache entry expires, the next lookup checks Redis (fast) before falling back to Cassandra (slow). This gives eventual consistency across instances with a propagation delay bounded by the local cache TTL.

The `SamplingResolver` also exposes `invalidateCache(String serviceId)` (line 295) for immediate local invalidation when a service registration is updated on the same instance. This provides instant consistency for local changes while TTL-based expiration handles cross-instance propagation.

### What a Senior Might Do Instead

A common alternative to hierarchical sampling is head-based sampling with a single rate, combined with tail-based sampling in the collector to keep interesting traces (errors, slow requests). Tail-based sampling is more powerful but requires buffering complete traces in the collector before making a sampling decision. For a gateway that processes millions of traces per minute, the collector memory requirements can be substantial.

Another approach is to skip custom sampling entirely and use OTel Collector's `tail_sampling` processor. This is simpler from the gateway's perspective but moves sampling decisions to infrastructure, where service teams have less control. The hierarchical approach gives service teams ownership of their sampling rates while the platform enforces guardrails.

---

## 8.8 Observability Backend Integration

### The Implementation

Aussie does not commit to a single observability backend. The platform documentation (`docs/platform/observability.md`) provides configurations for five tracing backends and three metrics backends, all using Quarkus profile-based configuration.

For tracing, the gateway uses OTLP (OpenTelemetry Protocol) export, which is supported by all major observability platforms:

```properties
# Jaeger (from docs/platform/observability.md, lines 85-86)
quarkus.otel.exporter.otlp.traces.endpoint=http://jaeger:4317

# Datadog (lines 116-118)
%datadog.quarkus.otel.exporter.otlp.traces.endpoint=https://trace.agent.datadoghq.com:443
%datadog.quarkus.otel.exporter.otlp.traces.headers=DD-API-KEY=${DD_API_KEY}

# New Relic (lines 129-130)
%newrelic.quarkus.otel.exporter.otlp.traces.endpoint=https://otlp.nr-data.net:4317
%newrelic.quarkus.otel.exporter.otlp.traces.headers=api-key=${NEW_RELIC_LICENSE_KEY}

# Splunk (lines 148-149)
%splunk.quarkus.otel.exporter.otlp.traces.endpoint=https://ingest.${SPLUNK_REALM}.signalfx.com:443
%splunk.quarkus.otel.exporter.otlp.traces.headers=X-SF-Token=${SPLUNK_ACCESS_TOKEN}
```

For metrics, the default is Prometheus scraping:

```properties
# Prometheus (from docs/platform/observability.md, lines 67-69)
quarkus.micrometer.export.prometheus.enabled=true
quarkus.micrometer.export.prometheus.path=/q/metrics
```

The Quarkus profile prefix (`%datadog.`, `%newrelic.`, `%splunk.`) allows switching backends without changing the gateway code or the default configuration. You activate a profile at deployment time via `QUARKUS_PROFILE=datadog`.

### Why OTLP for Everything

The decision to standardize on OTLP rather than vendor-specific exporters (Datadog agent protocol, New Relic proprietary API) is about organizational flexibility. If your company switches from Datadog to Grafana Cloud next year, you change a configuration file, not the gateway code. Every major observability vendor now supports OTLP ingestion, so this is a safe bet.

The trade-off is that vendor-specific features (Datadog's live tail, New Relic's custom attributes) may require vendor-specific configuration or additional exporter configuration. OTLP provides the lowest common denominator of trace data. For most gateway operations use cases, that is sufficient.

### Development Profile

The documentation includes a development observability stack (lines 428-443):

```properties
%dev.aussie.telemetry.enabled=true
%dev.quarkus.otel.exporter.otlp.traces.endpoint=http://localhost:4317
```

With `make up`, developers get Jaeger (port 16686), Prometheus (port 9090), and Grafana (port 3000) locally. This is critical for the observability feedback loop: if developers cannot see their traces and metrics locally, they will not instrument their code correctly. The observability stack must be as easy to start as the application itself.

---

## Summary of Key Design Principles

The observability decisions in this chapter reflect several recurring principles:

1. **Telemetry is off by default.** Every feature sits behind `aussie.telemetry.enabled=false`. This is a production safety decision. A misconfigured telemetry pipeline can take down a gateway faster than a traffic spike, either through resource exhaustion in the exporter or by saturating the network to the tracing backend.

2. **Configuration drift is more dangerous than runtime variation.** `BulkheadMetrics` exposes limits, not usage. The sampling system caches configs across instances. The attribution system normalizes dimensions to bounded tag sets. All of these reflect the insight that systemic problems (wrong config on one instance, missing tag on one service) are harder to detect and more damaging than transient ones (high CPU for 30 seconds, slow query for one request).

3. **Expected behavior is not an error.** Rate-limited requests get `StatusCode.OK`. This one decision -- two lines of code -- prevents the entire error alerting pipeline from being polluted by normal traffic management. It is the kind of decision that only becomes obvious after you have been woken up at 3 AM by a rate-limit-triggered error rate alert.

4. **Sealed types enforce exhaustive handling.** The `SecurityEvent` sealed interface means that adding a new event type is a compile error in every handler until it is handled. This is the type system doing the work of code review.

5. **Non-blocking by default, blocking only when necessary.** The `HierarchicalSampler` uses non-blocking resolution and accepts a stale rate on cache miss. The `SecurityEventDispatcher` uses async dispatch. The `TelemetryHelper` never allocates on the disabled path. In a gateway, every nanosecond in the request path is multiplied by millions of requests.
