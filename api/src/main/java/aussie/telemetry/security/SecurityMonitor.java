package aussie.telemetry.security;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import aussie.config.TelemetryConfig;
import aussie.telemetry.metrics.GatewayMetrics;

/**
 * Security monitoring service for detecting and alerting on security threats.
 *
 * <p>This service tracks request patterns and detects:
 * <ul>
 *   <li>Rate limit violations</li>
 *   <li>Brute force authentication attempts</li>
 *   <li>DoS attack patterns</li>
 *   <li>Suspicious error rate spikes</li>
 * </ul>
 *
 * <p>Detected events are recorded as metrics and can trigger alerts via
 * configured webhooks or CDI events.
 */
@ApplicationScoped
public class SecurityMonitor {

    private static final Logger LOG = Logger.getLogger(SecurityMonitor.class);

    @Inject
    TelemetryConfig config;

    @Inject
    GatewayMetrics metrics;

    @Inject
    Event<SecurityEvent> securityEventBus;

    // Sliding window counters per client IP
    private final ConcurrentHashMap<String, SlidingWindowCounter> clientRequestCounts = new ConcurrentHashMap<>();

    // Error counters per client IP
    private final ConcurrentHashMap<String, SlidingWindowCounter> clientErrorCounts = new ConcurrentHashMap<>();

    // Auth failure counters per client IP with timestamps for expiration
    private final ConcurrentHashMap<String, SlidingWindowCounter> authFailureCounts = new ConcurrentHashMap<>();

    /**
     * Records a request for security monitoring.
     *
     * @param clientIp client IP address
     * @param serviceId target service (may be null)
     * @param isError whether the request resulted in an error
     */
    public void recordRequest(String clientIp, String serviceId, boolean isError) {
        if (!config.security().enabled()) {
            return;
        }

        var counter = clientRequestCounts.computeIfAbsent(
                clientIp, k -> new SlidingWindowCounter(config.security().rateLimitWindow()));
        counter.increment();

        if (isError) {
            var errorCounter = clientErrorCounts.computeIfAbsent(
                    clientIp, k -> new SlidingWindowCounter(config.security().rateLimitWindow()));
            errorCounter.increment();
        }

        // Check for anomalies
        checkForAnomalies(clientIp, serviceId, counter.getCount(), isError);
    }

    /**
     * Records an authentication failure.
     *
     * @param clientIp client IP address
     * @param reason failure reason
     * @param credentialHint redacted credential hint
     */
    public void recordAuthFailure(String clientIp, String reason, String credentialHint) {
        if (!config.security().enabled()) {
            return;
        }

        var bruteForceConfig = config.security().bruteForce();
        var counter =
                authFailureCounts.computeIfAbsent(clientIp, k -> new SlidingWindowCounter(bruteForceConfig.window()));
        counter.increment();
        long count = counter.getCount();

        metrics.recordAuthFailure("api_key", reason, hashIp(clientIp));

        // Brute force detection
        if (bruteForceConfig.enabled() && count >= bruteForceConfig.failureThreshold()) {
            var event = new SecurityEvent.SuspiciousPattern(
                    Instant.now(),
                    clientIp,
                    "brute_force_attempt",
                    "Multiple auth failures: " + count,
                    Math.min(1.0, count / 10.0));

            publishSecurityEvent(event);
        }
    }

    /**
     * Records an access denied event.
     *
     * @param clientIp client IP address
     * @param serviceId target service
     * @param path requested path
     * @param reason denial reason
     */
    public void recordAccessDenied(String clientIp, String serviceId, String path, String reason) {
        if (!config.security().enabled()) {
            return;
        }

        var event = new SecurityEvent.AccessDenied(Instant.now(), clientIp, serviceId, path, reason);

        publishSecurityEvent(event);
        metrics.recordAccessDenied(serviceId, reason);
    }

    /**
     * Resets auth failure count for a client (e.g., after successful auth).
     *
     * @param clientIp client IP address
     */
    public void resetAuthFailures(String clientIp) {
        authFailureCounts.remove(clientIp);
    }

    /**
     * Returns the current request count for a client.
     *
     * @param clientIp client IP address
     * @return request count in the current window, or 0 if not tracked
     */
    public long getClientRequestCount(String clientIp) {
        var counter = clientRequestCounts.get(clientIp);
        return counter != null ? counter.getCount() : 0;
    }

    /**
     * Checks if a client is currently rate limited.
     *
     * @param clientIp client IP address
     * @return true if the client exceeds the rate limit threshold
     */
    public boolean isRateLimited(String clientIp) {
        var counter = clientRequestCounts.get(clientIp);
        return counter != null && counter.exceeds(config.security().rateLimitThreshold());
    }

    /**
     * Cleans up expired entries from tracking maps.
     *
     * <p>This method should be called periodically to prevent memory leaks
     * from inactive clients.
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        long windowMs = config.security().rateLimitWindow().toMillis();
        long bruteForceWindowMs = config.security().bruteForce().window().toMillis();

        // Clean up request counters
        clientRequestCounts.entrySet().removeIf(entry -> {
            var counter = entry.getValue();
            return counter.getCount() == 0
                    || (counter.getNewestTimestamp() > 0 && now - counter.getNewestTimestamp() > windowMs * 2);
        });

        // Clean up error counters
        clientErrorCounts.entrySet().removeIf(entry -> {
            var counter = entry.getValue();
            return counter.getCount() == 0
                    || (counter.getNewestTimestamp() > 0 && now - counter.getNewestTimestamp() > windowMs * 2);
        });

        // Clean up auth failure counters
        authFailureCounts.entrySet().removeIf(entry -> {
            var counter = entry.getValue();
            return counter.getCount() == 0
                    || (counter.getNewestTimestamp() > 0
                            && now - counter.getNewestTimestamp() > bruteForceWindowMs * 2);
        });
    }

    private void checkForAnomalies(String clientIp, String serviceId, long requestCount, boolean isError) {
        var secConfig = config.security();

        // Rate limit check
        if (requestCount > secConfig.rateLimitThreshold()) {
            var event = new SecurityEvent.RateLimitExceeded(
                    Instant.now(), clientIp, serviceId, (int) requestCount, secConfig.rateLimitThreshold());

            publishSecurityEvent(event);
            metrics.recordRateLimitViolation(hashIp(clientIp), serviceId != null ? serviceId : "global");
        }

        // DoS detection
        if (secConfig.dosDetection().enabled()) {
            detectDosPatterns(clientIp, serviceId, requestCount);
        }
    }

    private void detectDosPatterns(String clientIp, String serviceId, long requestCount) {
        var threshold = config.security().rateLimitThreshold();
        var spikeThreshold = config.security().dosDetection().requestSpikeThreshold();

        // Sudden spike detection
        if (requestCount > threshold * spikeThreshold) {
            var event = new SecurityEvent.DosAttackDetected(
                    Instant.now(),
                    clientIp,
                    "request_flood",
                    Map.of(
                            "request_count",
                            requestCount,
                            "threshold",
                            threshold,
                            "spike_factor",
                            requestCount / (double) threshold,
                            "service_id",
                            serviceId != null ? serviceId : "all"));

            publishSecurityEvent(event);

            LOG.warnf(
                    "Potential DoS attack detected from %s: %d requests (%.1fx threshold)",
                    hashIp(clientIp), requestCount, requestCount / (double) threshold);
        }

        // High error rate detection
        var errorCounter = clientErrorCounts.get(clientIp);
        if (errorCounter != null && requestCount > 0) {
            double errorRate = errorCounter.getCount() / (double) requestCount;
            var errorThreshold = config.security().dosDetection().errorRateThreshold();

            if (errorRate > errorThreshold) {
                var event = new SecurityEvent.SuspiciousPattern(
                        Instant.now(),
                        clientIp,
                        "high_error_rate",
                        String.format("Error rate: %.2f (threshold: %.2f)", errorRate, errorThreshold),
                        errorRate);

                publishSecurityEvent(event);
            }
        }
    }

    private void publishSecurityEvent(SecurityEvent event) {
        // Record metric
        metrics.recordSecurityEvent(event.getClass().getSimpleName());

        // Log event
        LOG.warnf("Security event: %s from %s", event.getClass().getSimpleName(), hashIp(event.clientIp()));

        // Fire CDI event for other listeners
        securityEventBus.fire(event);

        // Send to webhook if configured
        sendToWebhook(event);
    }

    private void sendToWebhook(SecurityEvent event) {
        Optional<String> webhookUrl = config.security().dosDetection().alertingWebhook();
        if (webhookUrl.isEmpty() || webhookUrl.get().isBlank()) {
            return;
        }

        // Only send critical events to webhook
        if (event instanceof SecurityEvent.DosAttackDetected || event instanceof SecurityEvent.RateLimitExceeded) {
            // In a real implementation, use an async HTTP client to POST to the webhook
            LOG.infof("Would send security alert to webhook: %s", webhookUrl.get());
        }
    }

    /**
     * Hashes an IP address for privacy in logs and metrics.
     *
     * @param ip IP address to hash
     * @return 8-character hex hash
     */
    private String hashIp(String ip) {
        if (ip == null) {
            return "unknown";
        }
        return Integer.toHexString(ip.hashCode())
                .substring(0, Math.min(8, Integer.toHexString(ip.hashCode()).length()));
    }
}
