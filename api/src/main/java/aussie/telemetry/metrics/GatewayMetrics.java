package aussie.telemetry.metrics;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import aussie.config.TelemetryConfig;

/**
 * Central metrics service for the Aussie Gateway.
 *
 * <p>This service provides methods for recording various gateway metrics:
 * <ul>
 *   <li>Request counters and latency histograms</li>
 *   <li>Traffic attribution for cost allocation</li>
 *   <li>Connection tracking (HTTP and WebSocket)</li>
 *   <li>Error and authentication metrics</li>
 * </ul>
 *
 * <p>All metrics are exported via Prometheus at {@code /q/metrics}.
 */
@ApplicationScoped
public class GatewayMetrics {

    @Inject
    MeterRegistry registry;

    @Inject
    TelemetryConfig config;

    // Connection gauges
    private final AtomicLong activeHttpConnections = new AtomicLong(0);
    private final AtomicLong activeWebSocketConnections = new AtomicLong(0);

    @PostConstruct
    void init() {
        // Register connection gauges
        Gauge.builder("aussie.connections.http.active", activeHttpConnections, AtomicLong::get)
                .description("Number of active HTTP connections")
                .register(registry);

        Gauge.builder("aussie.connections.websocket.active", activeWebSocketConnections, AtomicLong::get)
                .description("Number of active WebSocket connections")
                .register(registry);
    }

    // =========================================================================
    // Request Metrics
    // =========================================================================

    /**
     * Records a completed gateway request.
     *
     * @param serviceId target service identifier
     * @param method HTTP method
     * @param statusCode response status code
     * @param durationMs request duration in milliseconds
     */
    public void recordRequest(String serviceId, String method, int statusCode, long durationMs) {
        var tags = Tags.of(
                "service_id", sanitize(serviceId),
                "method", method,
                "status", String.valueOf(statusCode),
                "status_class", statusClass(statusCode));

        registry.counter("aussie.requests.total", tags).increment();

        Timer.builder("aussie.requests.duration")
                .tags(tags)
                .description("Request duration in milliseconds")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Records proxy latency (time to receive response from upstream).
     *
     * @param serviceId target service identifier
     * @param method HTTP method
     * @param upstreamStatusCode upstream response status code
     * @param latencyMs upstream latency in milliseconds
     */
    public void recordProxyLatency(String serviceId, String method, int upstreamStatusCode, long latencyMs) {
        var tags = Tags.of(
                "service_id", sanitize(serviceId),
                "method", method,
                "upstream_status", String.valueOf(upstreamStatusCode),
                "upstream_status_class", statusClass(upstreamStatusCode));

        Timer.builder("aussie.proxy.latency")
                .tags(tags)
                .description("Time to receive response from upstream service")
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .serviceLevelObjectives(config.metrics().proxyLatencyBuckets().stream()
                        .map(d -> Duration.ofMillis(d.longValue()))
                        .toArray(Duration[]::new))
                .register(registry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Records request and response sizes.
     *
     * @param serviceId target service identifier
     * @param requestBytes request body size in bytes
     * @param responseBytes response body size in bytes
     */
    public void recordRequestSize(String serviceId, long requestBytes, long responseBytes) {
        var tags = Tags.of("service_id", sanitize(serviceId));

        DistributionSummary.builder("aussie.request.size.bytes")
                .tags(tags)
                .description("Request body size in bytes")
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(registry)
                .record(requestBytes);

        DistributionSummary.builder("aussie.response.size.bytes")
                .tags(tags)
                .description("Response body size in bytes")
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(registry)
                .record(responseBytes);
    }

    // =========================================================================
    // Traffic Attribution
    // =========================================================================

    /**
     * Records traffic for cost attribution.
     *
     * @param serviceId target service identifier
     * @param teamId team identifier (may be null)
     * @param costCenter cost center (may be null)
     * @param tenantId tenant identifier (may be null)
     * @param requestBytes request size in bytes
     * @param responseBytes response size in bytes
     * @param durationMs request duration in milliseconds
     */
    public void recordTrafficAttribution(
            String serviceId,
            String teamId,
            String costCenter,
            String tenantId,
            long requestBytes,
            long responseBytes,
            long durationMs) {

        if (!config.trafficAttribution().enabled()) {
            return;
        }

        var tags = Tags.of(
                "service_id", sanitize(serviceId),
                "team_id", sanitize(teamId),
                "cost_center", sanitize(costCenter),
                "tenant_id", sanitize(tenantId));

        // Request count
        registry.counter("aussie.attributed.requests.total", tags).increment();

        // Data transfer
        registry.counter("aussie.attributed.bytes.ingress", tags).increment(requestBytes);
        registry.counter("aussie.attributed.bytes.egress", tags).increment(responseBytes);

        // Compute units (normalized cost metric)
        double computeUnits = calculateComputeUnits(requestBytes, responseBytes, durationMs);
        registry.counter("aussie.attributed.compute.units", tags).increment(computeUnits);

        // Duration for weighted averaging
        Timer.builder("aussie.attributed.duration")
                .tags(tags)
                .description("Request duration for attribution")
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    // =========================================================================
    // Error Metrics
    // =========================================================================

    /**
     * Records an error event.
     *
     * @param serviceId target service identifier
     * @param errorType error type classification
     */
    public void recordError(String serviceId, String errorType) {
        registry.counter(
                        "aussie.errors.total",
                        Tags.of("service_id", sanitize(serviceId), "error_type", sanitize(errorType)))
                .increment();
    }

    /**
     * Records a route not found error.
     *
     * @param path the requested path
     */
    public void recordRouteNotFound(String path) {
        registry.counter("aussie.errors.route_not_found.total").increment();
    }

    /**
     * Records a service not found error.
     *
     * @param serviceId the requested service ID
     */
    public void recordServiceNotFound(String serviceId) {
        registry.counter("aussie.errors.service_not_found.total", Tags.of("service_id", sanitize(serviceId)))
                .increment();
    }

    // =========================================================================
    // Authentication Metrics
    // =========================================================================

    /**
     * Records an authentication attempt.
     *
     * @param method authentication method (api_key, session, oidc)
     * @param success whether authentication succeeded
     */
    public void recordAuthAttempt(String method, boolean success) {
        var tags = Tags.of("method", sanitize(method), "result", success ? "success" : "failure");

        registry.counter("aussie.auth.attempts.total", tags).increment();
    }

    /**
     * Records an authentication failure.
     *
     * @param method authentication method
     * @param reason failure reason
     * @param clientIpHash hashed client IP for cardinality control
     */
    public void recordAuthFailure(String method, String reason, String clientIpHash) {
        var tags = Tags.of(
                "method", sanitize(method), "reason", sanitize(reason), "client_ip_hash", sanitize(clientIpHash));

        registry.counter("aussie.auth.failures.total", tags).increment();
    }

    /**
     * Records an access denied event.
     *
     * @param serviceId target service identifier
     * @param reason denial reason
     */
    public void recordAccessDenied(String serviceId, String reason) {
        registry.counter(
                        "aussie.access.denied.total",
                        Tags.of("service_id", sanitize(serviceId), "reason", sanitize(reason)))
                .increment();
    }

    // =========================================================================
    // Connection Metrics
    // =========================================================================

    /**
     * Increments the active HTTP connection count.
     */
    public void incrementHttpConnections() {
        activeHttpConnections.incrementAndGet();
    }

    /**
     * Decrements the active HTTP connection count.
     */
    public void decrementHttpConnections() {
        activeHttpConnections.decrementAndGet();
    }

    /**
     * Increments the active WebSocket connection count.
     */
    public void incrementWebSocketConnections() {
        activeWebSocketConnections.incrementAndGet();
    }

    /**
     * Decrements the active WebSocket connection count.
     */
    public void decrementWebSocketConnections() {
        activeWebSocketConnections.decrementAndGet();
    }

    /**
     * Returns the current WebSocket connection count.
     *
     * @return active WebSocket connections
     */
    public long getActiveWebSocketConnections() {
        return activeWebSocketConnections.get();
    }

    // =========================================================================
    // WebSocket Metrics
    // =========================================================================

    /**
     * Records a WebSocket connection event.
     *
     * @param serviceId target service identifier
     * @param event connection event type (opened, closed, error)
     */
    public void recordWebSocketEvent(String serviceId, String event) {
        registry.counter(
                        "aussie.websocket.events.total",
                        Tags.of("service_id", sanitize(serviceId), "event", sanitize(event)))
                .increment();
    }

    /**
     * Records WebSocket message metrics.
     *
     * @param serviceId target service identifier
     * @param direction message direction (inbound, outbound)
     * @param sizeBytes message size in bytes
     */
    public void recordWebSocketMessage(String serviceId, String direction, long sizeBytes) {
        var tags = Tags.of("service_id", sanitize(serviceId), "direction", direction);

        registry.counter("aussie.websocket.messages.total", tags).increment();

        DistributionSummary.builder("aussie.websocket.message.size.bytes")
                .tags(tags)
                .description("WebSocket message size in bytes")
                .register(registry)
                .record(sizeBytes);
    }

    /**
     * Records WebSocket connection duration.
     *
     * @param serviceId target service identifier
     * @param durationMs connection duration in milliseconds
     */
    public void recordWebSocketDuration(String serviceId, long durationMs) {
        Timer.builder("aussie.websocket.connection.duration")
                .tags(Tags.of("service_id", sanitize(serviceId)))
                .description("WebSocket connection duration")
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    // =========================================================================
    // Security Metrics
    // =========================================================================

    /**
     * Records a security event.
     *
     * @param eventType type of security event
     */
    public void recordSecurityEvent(String eventType) {
        registry.counter("aussie.security.events.total", Tags.of("event_type", sanitize(eventType)))
                .increment();
    }

    /**
     * Records a rate limit violation.
     *
     * @param clientIpHash hashed client IP
     * @param serviceId target service (may be null for global limits)
     */
    public void recordRateLimitViolation(String clientIpHash, String serviceId) {
        registry.counter(
                        "aussie.security.rate_limit.exceeded.total",
                        Tags.of("client_ip_hash", sanitize(clientIpHash), "service_id", sanitize(serviceId)))
                .increment();
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Calculates compute units for cost attribution.
     *
     * <p>Formula: base cost + data cost + time cost
     */
    private double calculateComputeUnits(long requestBytes, long responseBytes, long durationMs) {
        double baseCost = 1.0;
        double dataCost = (requestBytes + responseBytes) / 10_000.0;
        double timeCost = durationMs / 100.0;
        return baseCost + dataCost + timeCost;
    }

    /**
     * Sanitizes a tag value for safe metric storage.
     */
    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        // Limit length to prevent high cardinality
        if (value.length() > 64) {
            return value.substring(0, 64);
        }
        return value;
    }

    /**
     * Returns the status class (1xx, 2xx, etc.) for a status code.
     */
    private String statusClass(int statusCode) {
        return (statusCode / 100) + "xx";
    }
}
