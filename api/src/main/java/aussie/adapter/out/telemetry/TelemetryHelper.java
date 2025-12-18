package aussie.adapter.out.telemetry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.opentelemetry.api.trace.Span;

/**
 * Helper for conditionally adding span attributes based on configuration.
 *
 * <p>Use this helper to ensure attributes are only added when enabled,
 * reducing cardinality and storage costs in telemetry backends.
 *
 * <p>All methods respect the master telemetry switch ({@code aussie.telemetry.enabled})
 * and the tracing switch ({@code aussie.telemetry.tracing.enabled}).
 */
@ApplicationScoped
public class TelemetryHelper {

    private final TelemetryConfig config;

    @Inject
    public TelemetryHelper(TelemetryConfig config) {
        this.config = config;
    }

    /**
     * Check if telemetry tracing is enabled (master switch + tracing switch).
     * All attribute setters use this as a gate.
     */
    private boolean isTracingEnabled() {
        return config.enabled() && config.tracing().enabled();
    }

    private TelemetryConfig.AttributesConfig attrs() {
        return config.attributes();
    }

    // -------------------------------------------------------------------------
    // Request/Response Sizing
    // -------------------------------------------------------------------------

    public void setRequestSize(Span span, long value) {
        if (isTracingEnabled() && attrs().requestSize()) {
            span.setAttribute(SpanAttributes.REQUEST_SIZE, value);
        }
    }

    public void setResponseSize(Span span, long value) {
        if (isTracingEnabled() && attrs().responseSize()) {
            span.setAttribute(SpanAttributes.RESPONSE_SIZE, value);
        }
    }

    // -------------------------------------------------------------------------
    // Upstream Attributes
    // -------------------------------------------------------------------------

    public void setUpstreamHost(Span span, String value) {
        if (isTracingEnabled() && attrs().upstreamHost()) {
            span.setAttribute(SpanAttributes.UPSTREAM_HOST, value);
        }
    }

    public void setUpstreamPort(Span span, int value) {
        if (isTracingEnabled() && attrs().upstreamPort()) {
            span.setAttribute(SpanAttributes.UPSTREAM_PORT, value);
        }
    }

    public void setUpstreamUri(Span span, String value) {
        if (isTracingEnabled() && attrs().upstreamUri()) {
            span.setAttribute(SpanAttributes.UPSTREAM_URI, value);
        }
    }

    public void setUpstreamLatency(Span span, long value) {
        if (isTracingEnabled() && attrs().upstreamLatency()) {
            span.setAttribute(SpanAttributes.UPSTREAM_LATENCY_MS, value);
        }
    }

    // -------------------------------------------------------------------------
    // Rate Limiting Attributes
    // -------------------------------------------------------------------------

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

    public void setRateLimitType(Span span, String value) {
        if (isTracingEnabled() && attrs().rateLimitType()) {
            span.setAttribute(SpanAttributes.RATE_LIMIT_TYPE, value);
        }
    }

    public void setRateLimitRetryAfter(Span span, long value) {
        if (isTracingEnabled() && attrs().rateLimitRetryAfter()) {
            span.setAttribute(SpanAttributes.RATE_LIMIT_RETRY_AFTER, value);
        }
    }
}
