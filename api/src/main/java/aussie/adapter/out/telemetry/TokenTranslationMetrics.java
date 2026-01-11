package aussie.adapter.out.telemetry;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import aussie.core.model.auth.TranslationOutcome;
import aussie.core.port.out.TranslationMetrics;

/**
 * Metrics service for token translation operations.
 *
 * <p>Records metrics for:
 * <ul>
 *   <li>{@code aussie.token.translation.total} - Total translations by provider and outcome</li>
 *   <li>{@code aussie.token.translation.duration} - Translation latency histogram</li>
 *   <li>{@code aussie.token.translation.cache.hits} - Cache hit count</li>
 *   <li>{@code aussie.token.translation.cache.misses} - Cache miss count</li>
 *   <li>{@code aussie.token.translation.cache.size} - Current cache size gauge</li>
 *   <li>{@code aussie.token.translation.errors} - Translation error count by type</li>
 * </ul>
 */
@ApplicationScoped
public class TokenTranslationMetrics implements TranslationMetrics {

    private final MeterRegistry registry;
    private final TelemetryConfig config;
    private final boolean enabled;

    // Cache size gauges
    private final AtomicLong cacheSize = new AtomicLong(0);

    @Inject
    public TokenTranslationMetrics(MeterRegistry registry, TelemetryConfig config) {
        this.registry = registry;
        this.config = config;
        this.enabled = config != null && config.enabled() && config.metrics().enabled();
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            return;
        }

        Gauge.builder("aussie.token.translation.cache.size", cacheSize, AtomicLong::get)
                .description("Current number of entries in the translation cache")
                .register(registry);
    }

    /**
     * Check if metrics recording is enabled.
     *
     * @return true if metrics are enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void recordTranslation(String provider, TranslationOutcome outcome, long durationMs) {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.token.translation.total")
                .description("Total token translation operations")
                .tag("provider", nullSafe(provider))
                .tag("outcome", outcome.name().toLowerCase())
                .register(registry)
                .increment();

        Timer.builder("aussie.token.translation.duration")
                .description("Token translation latency")
                .tag("provider", nullSafe(provider))
                .tag("outcome", outcome.name().toLowerCase())
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordCacheHit() {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.token.translation.cache.hits")
                .description("Token translation cache hits")
                .register(registry)
                .increment();
    }

    @Override
    public void recordCacheMiss() {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.token.translation.cache.misses")
                .description("Token translation cache misses")
                .register(registry)
                .increment();
    }

    @Override
    public void updateCacheSize(long size) {
        cacheSize.set(size);
    }

    @Override
    public void recordError(String provider, String errorType) {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.token.translation.errors")
                .description("Token translation errors")
                .tag("provider", nullSafe(provider))
                .tag("error_type", nullSafe(errorType))
                .register(registry)
                .increment();
    }

    /**
     * Record a remote translation call.
     *
     * @param statusCode the HTTP status code returned
     * @param durationMs duration in milliseconds
     */
    public void recordRemoteCall(int statusCode, long durationMs) {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.token.translation.remote.total")
                .description("Remote translation service calls")
                .tag("status", String.valueOf(statusCode))
                .tag("status_class", statusClass(statusCode))
                .register(registry)
                .increment();

        Timer.builder("aussie.token.translation.remote.duration")
                .description("Remote translation service latency")
                .tag("status_class", statusClass(statusCode))
                .publishPercentiles(0.5, 0.9, 0.95, 0.99)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Record a config reload.
     *
     * @param success whether the reload was successful
     */
    public void recordConfigReload(boolean success) {
        if (!enabled) {
            return;
        }

        Counter.builder("aussie.token.translation.config.reloads")
                .description("Token translation config reload events")
                .tag("success", String.valueOf(success))
                .register(registry)
                .increment();
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
}
