package aussie.config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Configuration mapping for telemetry, observability, and security monitoring.
 *
 * <p>Configuration prefix: {@code aussie.telemetry}
 *
 * <p>This configuration controls:
 * <ul>
 *   <li>Traffic attribution for cost allocation across teams</li>
 *   <li>Security monitoring and DoS detection</li>
 *   <li>Custom metrics collection settings</li>
 * </ul>
 *
 * <p>OpenTelemetry tracing and Prometheus metrics are configured via standard
 * Quarkus properties ({@code quarkus.otel.*} and {@code quarkus.micrometer.*}).
 *
 * @see <a href="https://quarkus.io/guides/opentelemetry">Quarkus OpenTelemetry Guide</a>
 * @see <a href="https://quarkus.io/guides/micrometer">Quarkus Micrometer Guide</a>
 */
@ConfigMapping(prefix = "aussie.telemetry")
public interface TelemetryConfig {

    /**
     * Traffic attribution configuration for cost allocation.
     */
    @WithName("traffic-attribution")
    TrafficAttributionConfig trafficAttribution();

    /**
     * Security monitoring and DoS detection configuration.
     */
    SecurityConfig security();

    /**
     * Custom metrics configuration.
     */
    MetricsConfig metrics();

    /**
     * Traffic attribution settings for distributing costs across teams.
     *
     * <p>When enabled, Aussie tracks request volume, data transfer, and compute
     * units per service, team, and cost center. This data can be exported via
     * Prometheus and used for chargeback reporting.
     */
    interface TrafficAttributionConfig {

        /**
         * Enable traffic attribution tracking.
         *
         * @return true if attribution is enabled (default: true)
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Include request/response headers in attribution metrics.
         *
         * <p>WARNING: Enabling this may increase metric cardinality significantly
         * and could expose sensitive header values in metrics.
         *
         * @return true if headers should be included (default: false)
         */
        @WithDefault("false")
        boolean includeHeaders();

        /**
         * Header name containing the team identifier.
         *
         * @return header name (default: X-Team-ID)
         */
        @WithName("team-header")
        @WithDefault("X-Team-ID")
        String teamHeader();

        /**
         * Header name containing the cost center identifier.
         *
         * @return header name (default: X-Cost-Center)
         */
        @WithName("cost-center-header")
        @WithDefault("X-Cost-Center")
        String costCenterHeader();

        /**
         * Header name containing the tenant identifier (for multi-tenant setups).
         *
         * @return header name (default: X-Tenant-ID)
         */
        @WithName("tenant-header")
        @WithDefault("X-Tenant-ID")
        String tenantHeader();
    }

    /**
     * Security monitoring configuration for detecting and mitigating attacks.
     */
    interface SecurityConfig {

        /**
         * Enable security monitoring and anomaly detection.
         *
         * @return true if security monitoring is enabled (default: true)
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Time window for rate limiting calculations.
         *
         * @return window duration (default: 60 seconds)
         */
        @WithName("rate-limit-window")
        @WithDefault("PT60S")
        Duration rateLimitWindow();

        /**
         * Maximum requests per client within the rate limit window.
         *
         * <p>Requests exceeding this threshold trigger rate limit metrics
         * and potentially security alerts.
         *
         * @return threshold count (default: 1000)
         */
        @WithName("rate-limit-threshold")
        @WithDefault("1000")
        int rateLimitThreshold();

        /**
         * DoS detection configuration.
         */
        @WithName("dos-detection")
        DosDetectionConfig dosDetection();

        /**
         * Brute force detection configuration.
         */
        @WithName("brute-force")
        BruteForceConfig bruteForce();

        /**
         * DoS (Denial of Service) detection settings.
         */
        interface DosDetectionConfig {

            /**
             * Enable DoS attack pattern detection.
             *
             * @return true if DoS detection is enabled (default: true)
             */
            @WithDefault("true")
            boolean enabled();

            /**
             * Request spike threshold multiplier.
             *
             * <p>When a client's request rate exceeds the rate limit threshold
             * multiplied by this factor, a DoS alert is triggered.
             *
             * @return spike multiplier (default: 5.0)
             */
            @WithName("request-spike-threshold")
            @WithDefault("5.0")
            double requestSpikeThreshold();

            /**
             * Error rate threshold for anomaly detection.
             *
             * <p>When a client's error rate exceeds this percentage (0.0 to 1.0),
             * suspicious activity is flagged.
             *
             * @return error rate threshold (default: 0.5 = 50%)
             */
            @WithName("error-rate-threshold")
            @WithDefault("0.5")
            double errorRateThreshold();

            /**
             * Webhook URL for alerting on detected attacks.
             *
             * <p>When configured, HTTP POST requests are sent to this URL
             * with attack details in JSON format.
             *
             * @return webhook URL (optional)
             */
            @WithName("alerting-webhook")
            Optional<String> alertingWebhook();
        }

        /**
         * Brute force attack detection settings.
         */
        interface BruteForceConfig {

            /**
             * Enable brute force attack detection.
             *
             * @return true if detection is enabled (default: true)
             */
            @WithDefault("true")
            boolean enabled();

            /**
             * Authentication failure threshold before flagging as brute force.
             *
             * @return failure count threshold (default: 5)
             */
            @WithName("failure-threshold")
            @WithDefault("5")
            int failureThreshold();

            /**
             * Time window for counting authentication failures.
             *
             * @return window duration (default: 5 minutes)
             */
            @WithName("window")
            @WithDefault("PT5M")
            Duration window();
        }
    }

    /**
     * Custom metrics collection settings.
     */
    interface MetricsConfig {

        /**
         * Histogram buckets for proxy latency measurements (in milliseconds).
         *
         * <p>These values define the bucket boundaries for the proxy latency histogram,
         * allowing for percentile calculations.
         *
         * @return bucket boundaries in milliseconds
         */
        @WithName("proxy-latency-buckets")
        @WithDefault("5,10,25,50,100,250,500,1000,2500,5000,10000")
        List<Double> proxyLatencyBuckets();

        /**
         * Histogram buckets for request size measurements (in bytes).
         *
         * @return bucket boundaries in bytes
         */
        @WithName("request-size-buckets")
        @WithDefault("100,1000,10000,100000,1000000")
        List<Double> requestSizeBuckets();

        /**
         * Enable per-endpoint metrics.
         *
         * <p>WARNING: Enabling this with many unique endpoints can cause
         * high metric cardinality.
         *
         * @return true if per-endpoint metrics are enabled (default: false)
         */
        @WithName("per-endpoint")
        @WithDefault("false")
        boolean perEndpoint();

        /**
         * Maximum cardinality for dynamic metric labels.
         *
         * <p>Limits the number of unique label combinations to prevent
         * metric explosion.
         *
         * @return max cardinality (default: 1000)
         */
        @WithName("max-cardinality")
        @WithDefault("1000")
        int maxCardinality();
    }
}
