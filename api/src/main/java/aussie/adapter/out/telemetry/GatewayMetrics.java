package aussie.adapter.out.telemetry;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import aussie.config.TelemetryConfigMapping;
import aussie.core.model.GatewayResult;
import aussie.core.port.out.Metrics;

/**
 * Central service for recording gateway metrics using Micrometer.
 *
 * <p>All methods are no-ops when telemetry is disabled, making it safe
 * to inject and call without checking configuration at each call site.
 *
 * <p>Metrics recorded:
 * <ul>
 *   <li>{@code aussie.requests.total} - Total request count by service, method, status</li>
 *   <li>{@code aussie.proxy.latency} - Upstream response latency histogram</li>
 *   <li>{@code aussie.gateway.results} - Gateway result types (success, error, unauthorized, etc.)</li>
 *   <li>{@code aussie.traffic.bytes} - Traffic volume by service and direction</li>
 *   <li>{@code aussie.errors.total} - Error count by service and error type</li>
 *   <li>{@code aussie.auth.failures.total} - Authentication failure count</li>
 *   <li>{@code aussie.access.denied.total} - Access denied count</li>
 *   <li>{@code aussie.connections.active} - Active HTTP connections gauge</li>
 *   <li>{@code aussie.websockets.active} - Active WebSocket connections gauge</li>
 * </ul>
 */
@ApplicationScoped
public class GatewayMetrics implements Metrics {

    private final MeterRegistry registry;
    private final TelemetryConfigMapping config;
    private final boolean enabled;

    // Connection gauges
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong activeWebSockets = new AtomicLong(0);

    @Inject
    public GatewayMetrics(MeterRegistry registry, TelemetryConfigMapping config) {
        this.registry = registry;
        this.config = config;
        this.enabled = config != null && config.enabled() && config.metrics().enabled();
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            return;
        }

        // Register connection gauges
        Gauge.builder("aussie.connections.active", activeConnections, AtomicLong::get)
                .description("Number of active HTTP connections")
                .register(registry);

        Gauge.builder("aussie.websockets.active", activeWebSockets, AtomicLong::get)
                .description("Number of active WebSocket connections")
                .register(registry);
    }

    /**
     * Check if metrics recording is enabled.
     *
     * @return true if metrics are enabled
     */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // -------------------------------------------------------------------------
    // Request Metrics
    // -------------------------------------------------------------------------

    /**
     * Record a gateway request with its outcome.
     *
     * @param serviceId the target service ID (may be null for route not found)
     * @param method the HTTP method
     * @param statusCode the response status code
     */
    @Override
    public void recordRequest(String serviceId, String method, int statusCode) {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.requests.total")
                .description("Total number of requests processed")
                .tag("service_id", nullSafe(serviceId))
                .tag("method", method)
                .tag("status", String.valueOf(statusCode))
                .tag("status_class", statusClass(statusCode))
                .register(registry)
                .increment();
    }

    /**
     * Record proxy latency to upstream service.
     *
     * @param serviceId the target service ID
     * @param method the HTTP method
     * @param statusCode the response status code
     * @param latencyMs latency in milliseconds
     */
    @Override
    public void recordProxyLatency(String serviceId, String method, int statusCode, long latencyMs) {
        if (!enabled) {
            return;
        }

        Timer.builder("aussie.proxy.latency")
                .description("Time to receive response from upstream service")
                .tag("service_id", nullSafe(serviceId))
                .tag("method", method)
                .tag("status_class", statusClass(statusCode))
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry)
                .record(latencyMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record a gateway result type.
     *
     * @param serviceId the target service ID (may be null)
     * @param result the gateway result
     */
    @Override
    public void recordGatewayResult(String serviceId, GatewayResult result) {
        if (!enabled) {
            return;
        }

        var resultType = getResultType(result);

        Counter.builder("aussie.gateway.results")
                .description("Gateway result types")
                .tag("service_id", nullSafe(serviceId))
                .tag("result_type", resultType)
                .register(registry)
                .increment();
    }

    // -------------------------------------------------------------------------
    // Traffic Metrics
    // -------------------------------------------------------------------------

    /**
     * Record traffic volume for cost attribution.
     *
     * @param serviceId the target service ID
     * @param teamId the team identifier (may be null)
     * @param requestBytes incoming request size in bytes
     * @param responseBytes outgoing response size in bytes
     */
    @Override
    public void recordTraffic(String serviceId, String teamId, long requestBytes, long responseBytes) {
        if (!enabled) {
            return;
        }

        var tags = Tags.of(
                "service_id", nullSafe(serviceId),
                "team_id", nullSafe(teamId));

        Counter.builder("aussie.traffic.bytes")
                .description("Traffic volume in bytes")
                .tags(tags)
                .tag("direction", "inbound")
                .register(registry)
                .increment(requestBytes);

        Counter.builder("aussie.traffic.bytes")
                .description("Traffic volume in bytes")
                .tags(tags)
                .tag("direction", "outbound")
                .register(registry)
                .increment(responseBytes);
    }

    // -------------------------------------------------------------------------
    // Error Metrics
    // -------------------------------------------------------------------------

    /**
     * Record an error.
     *
     * @param serviceId the target service ID
     * @param errorType the type of error (e.g., "upstream_timeout", "connection_refused")
     */
    @Override
    public void recordError(String serviceId, String errorType) {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.errors.total")
                .description("Total number of errors")
                .tag("service_id", nullSafe(serviceId))
                .tag("error_type", errorType)
                .register(registry)
                .increment();
    }

    // -------------------------------------------------------------------------
    // Authentication Metrics
    // -------------------------------------------------------------------------

    /**
     * Record an authentication failure.
     *
     * @param reason the failure reason (e.g., "invalid_key", "expired_session")
     * @param clientIp the client IP address (will be hashed for privacy)
     */
    @Override
    public void recordAuthFailure(String reason, String clientIp) {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.auth.failures.total")
                .description("Authentication failures")
                .tag("reason", reason)
                .tag("client_ip_hash", hashIp(clientIp))
                .register(registry)
                .increment();
    }

    /**
     * Record a successful authentication.
     *
     * @param method the authentication method (api_key, session, jwt)
     */
    @Override
    public void recordAuthSuccess(String method) {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.auth.success.total")
                .description("Successful authentications")
                .tag("method", method)
                .register(registry)
                .increment();
    }

    /**
     * Record an access denied event.
     *
     * @param serviceId the target service ID
     * @param reason the denial reason (e.g., "ip_blocked", "visibility_private")
     */
    @Override
    public void recordAccessDenied(String serviceId, String reason) {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.access.denied.total")
                .description("Access denied events")
                .tag("service_id", nullSafe(serviceId))
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    // -------------------------------------------------------------------------
    // Connection Metrics
    // -------------------------------------------------------------------------

    /**
     * Increment active HTTP connection count.
     */
    @Override
    public void incrementActiveConnections() {
        if (!enabled) {
            return;
        }
        activeConnections.incrementAndGet();
    }

    /**
     * Decrement active HTTP connection count.
     */
    @Override
    public void decrementActiveConnections() {
        if (!enabled) {
            return;
        }
        activeConnections.decrementAndGet();
    }

    /**
     * Increment active WebSocket connection count.
     */
    @Override
    public void incrementActiveWebSockets() {
        if (!enabled) {
            return;
        }
        activeWebSockets.incrementAndGet();
    }

    /**
     * Decrement active WebSocket connection count.
     */
    @Override
    public void decrementActiveWebSockets() {
        if (!enabled) {
            return;
        }
        activeWebSockets.decrementAndGet();
    }

    /**
     * Get current active WebSocket count.
     *
     * @return active WebSocket count
     */
    public long getActiveWebSockets() {
        return activeWebSockets.get();
    }

    // -------------------------------------------------------------------------
    // WebSocket Metrics
    // -------------------------------------------------------------------------

    /**
     * Record WebSocket connection establishment.
     *
     * @param serviceId the backend service ID
     */
    @Override
    public void recordWebSocketConnect(String serviceId) {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.websocket.connections.total")
                .description("Total WebSocket connections established")
                .tag("service_id", nullSafe(serviceId))
                .register(registry)
                .increment();
    }

    /**
     * Record WebSocket connection closure.
     *
     * @param serviceId the backend service ID
     * @param durationMs connection duration in milliseconds
     */
    @Override
    public void recordWebSocketDisconnect(String serviceId, long durationMs) {
        if (!enabled) {
            return;
        }

        Timer.builder("aussie.websocket.duration")
                .description("WebSocket connection duration")
                .tag("service_id", nullSafe(serviceId))
                .publishPercentiles(0.5, 0.9, 0.99)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record WebSocket connection limit reached.
     */
    @Override
    public void recordWebSocketLimitReached() {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.websocket.limit.reached")
                .description("WebSocket connection limit reached events")
                .register(registry)
                .increment();
    }

    // -------------------------------------------------------------------------
    // Rate Limiting Metrics
    // -------------------------------------------------------------------------

    /**
     * Record a rate limit check.
     *
     * @param serviceId the target service ID
     * @param allowed whether the request was allowed
     * @param remaining remaining requests in window
     */
    @Override
    public void recordRateLimitCheck(String serviceId, boolean allowed, long remaining) {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.ratelimit.checks.total")
                .description("Total rate limit checks")
                .tag("service_id", nullSafe(serviceId))
                .tag("allowed", String.valueOf(allowed))
                .register(registry)
                .increment();
    }

    /**
     * Record a rate limit exceeded event.
     *
     * @param serviceId the target service ID
     * @param limitType the type of limit (http, ws_connection, ws_message)
     */
    @Override
    public void recordRateLimitExceeded(String serviceId, String limitType) {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.ratelimit.exceeded.total")
                .description("Rate limit exceeded events")
                .tag("service_id", nullSafe(serviceId))
                .tag("limit_type", limitType)
                .register(registry)
                .increment();
    }

    // -------------------------------------------------------------------------
    // Helper Methods
    // -------------------------------------------------------------------------

    private String getResultType(GatewayResult result) {
        return switch (result) {
            case GatewayResult.Success s -> "success";
            case GatewayResult.RouteNotFound r -> "route_not_found";
            case GatewayResult.ServiceNotFound s -> "service_not_found";
            case GatewayResult.ReservedPath r -> "reserved_path";
            case GatewayResult.Error e -> "error";
            case GatewayResult.Unauthorized u -> "unauthorized";
            case GatewayResult.Forbidden f -> "forbidden";
            case GatewayResult.BadRequest b -> "bad_request";
        };
    }

    private String statusClass(int statusCode) {
        return switch (statusCode / 100) {
            case 1 -> "1xx";
            case 2 -> "2xx";
            case 3 -> "3xx";
            case 4 -> "4xx";
            case 5 -> "5xx";
            default -> "unknown";
        };
    }

    private String nullSafe(String value) {
        return value != null ? value : "unknown";
    }

    private String hashIp(String ip) {
        if (ip == null) {
            return "unknown";
        }
        // Hash IP for privacy - only keep first 8 hex chars
        var hash = Integer.toHexString(ip.hashCode());
        return hash.substring(0, Math.min(8, hash.length()));
    }
}
