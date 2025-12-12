package aussie.config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Configuration for telemetry features including tracing, metrics, and security monitoring.
 *
 * <p>All telemetry features are disabled by default. Platform teams must explicitly enable
 * the features they need via configuration.
 *
 * <p>Example configuration:
 * <pre>{@code
 * aussie.telemetry.enabled=true
 * aussie.telemetry.tracing.enabled=true
 * aussie.telemetry.metrics.enabled=true
 * aussie.telemetry.security.enabled=true
 * }</pre>
 */
@ConfigMapping(prefix = "aussie.telemetry")
public interface TelemetryConfigMapping {

    /**
     * Master toggle for all telemetry features.
     * When disabled, all sub-features are also disabled regardless of their individual settings.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Tracing configuration for distributed tracing with OpenTelemetry.
     */
    TracingConfig tracing();

    /**
     * Metrics configuration for Micrometer metrics collection.
     */
    MetricsConfig metrics();

    /**
     * Security monitoring configuration for anomaly detection and alerting.
     */
    SecurityConfig security();

    /**
     * Traffic attribution configuration for cost allocation.
     */
    AttributionConfig attribution();

    /**
     * Tracing configuration.
     */
    interface TracingConfig {
        /**
         * Enable distributed tracing with OpenTelemetry.
         * Requires aussie.telemetry.enabled=true to take effect.
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Sampling rate for traces (0.0 to 1.0).
         * Only used when parent-based sampling is not in effect.
         */
        @WithName("sampling-rate")
        @WithDefault("1.0")
        double samplingRate();
    }

    /**
     * Metrics configuration.
     */
    interface MetricsConfig {
        /**
         * Enable metrics collection with Micrometer.
         * Requires aussie.telemetry.enabled=true to take effect.
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Histogram buckets for proxy latency metrics (in milliseconds).
         */
        @WithName("proxy-latency-buckets")
        @WithDefault("5,10,25,50,100,250,500,1000,2500,5000,10000")
        List<Double> proxyLatencyBuckets();

        /**
         * Histogram buckets for request size metrics (in bytes).
         */
        @WithName("request-size-buckets")
        @WithDefault("100,1000,10000,100000,1000000")
        List<Double> requestSizeBuckets();
    }

    /**
     * Security monitoring configuration.
     */
    interface SecurityConfig {
        /**
         * Enable security monitoring for anomaly detection.
         * Requires aussie.telemetry.enabled=true to take effect.
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Time window for rate limiting calculations.
         */
        @WithName("rate-limit-window")
        @WithDefault("PT1M")
        Duration rateLimitWindow();

        /**
         * Request threshold within rate limit window before triggering alerts.
         */
        @WithName("rate-limit-threshold")
        @WithDefault("1000")
        int rateLimitThreshold();

        /**
         * DoS detection configuration.
         */
        DosDetectionConfig dosDetection();

        /**
         * DoS detection configuration.
         */
        interface DosDetectionConfig {
            /**
             * Enable DoS attack pattern detection.
             */
            @WithDefault("true")
            boolean enabled();

            /**
             * Spike threshold multiplier.
             * Request count exceeding (rateLimitThreshold * spikeThreshold) triggers alert.
             */
            @WithName("spike-threshold")
            @WithDefault("5.0")
            double spikeThreshold();

            /**
             * Error rate threshold (0.0 to 1.0).
             * Clients with error rates above this trigger suspicious activity alerts.
             */
            @WithName("error-rate-threshold")
            @WithDefault("0.5")
            double errorRateThreshold();
        }
    }

    /**
     * Traffic attribution configuration for cost allocation.
     */
    interface AttributionConfig {
        /**
         * Enable traffic attribution metrics for cost allocation.
         * Requires aussie.telemetry.enabled=true to take effect.
         */
        @WithDefault("false")
        boolean enabled();

        /**
         * Include request headers in attribution dimensions.
         * May impact performance and increase metric cardinality.
         */
        @WithName("include-headers")
        @WithDefault("false")
        boolean includeHeaders();

        /**
         * Header name for team identification.
         */
        @WithName("team-header")
        @WithDefault("X-Team-ID")
        String teamHeader();

        /**
         * Header name for tenant identification.
         */
        @WithName("tenant-header")
        @WithDefault("X-Tenant-ID")
        String tenantHeader();

        /**
         * Header name for client application identification.
         */
        @WithName("client-app-header")
        @WithDefault("X-Client-Application")
        String clientAppHeader();

        /**
         * Custom dimension headers to include in attribution.
         * Comma-separated list of header names.
         */
        @WithName("custom-dimensions")
        Optional<List<String>> customDimensions();
    }
}
