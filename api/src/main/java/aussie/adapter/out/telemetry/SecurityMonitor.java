package aussie.adapter.out.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import aussie.config.TelemetryConfigMapping;
import aussie.core.port.out.SecurityMonitoring;
import aussie.spi.SecurityEvent;

/**
 * Monitors traffic patterns and detects security anomalies.
 *
 * <p>The monitor tracks request rates, error rates, and authentication failures
 * per client. When thresholds are exceeded, security events are generated and
 * dispatched to registered handlers.
 *
 * <p>Detection capabilities:
 * <ul>
 *   <li>Rate limit violations</li>
 *   <li>Brute force authentication attempts</li>
 *   <li>Request flood attacks (DoS)</li>
 *   <li>High error rate clients</li>
 * </ul>
 */
@ApplicationScoped
public class SecurityMonitor implements SecurityMonitoring {

    private static final Logger LOG = Logger.getLogger(SecurityMonitor.class);

    private final TelemetryConfigMapping config;
    private final SecurityEventDispatcher dispatcher;
    private final boolean enabled;

    // Per-client request tracking
    private final ConcurrentHashMap<String, SlidingWindowCounter> clientRequestCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SlidingWindowCounter> clientErrorCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> authFailureCounts = new ConcurrentHashMap<>();

    @Inject
    public SecurityMonitor(TelemetryConfigMapping config, SecurityEventDispatcher dispatcher) {
        this.config = config;
        this.dispatcher = dispatcher;
        this.enabled = config != null && config.enabled() && config.security().enabled();
    }

    /**
     * Check if security monitoring is enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Record a request from a client.
     *
     * @param clientIp the client IP address
     * @param serviceId the target service (may be null)
     * @param isError whether the request resulted in an error
     */
    public void recordRequest(String clientIp, String serviceId, boolean isError) {
        if (!enabled) {
            return;
        }

        var window = config.security().rateLimitWindow();

        var counter = clientRequestCounts.computeIfAbsent(hashIp(clientIp), k -> new SlidingWindowCounter(window));
        counter.increment();

        if (isError) {
            var errorCounter =
                    clientErrorCounts.computeIfAbsent(hashIp(clientIp), k -> new SlidingWindowCounter(window));
            errorCounter.increment();
        }

        checkForAnomalies(clientIp, serviceId, counter.getCount());
    }

    /**
     * Record an authentication failure.
     *
     * @param clientIp the client IP address
     * @param reason the failure reason
     * @param method the authentication method attempted
     */
    public void recordAuthFailure(String clientIp, String reason, String method) {
        if (!enabled) {
            return;
        }

        var hashedIp = hashIp(clientIp);
        var count = authFailureCounts
                .computeIfAbsent(hashedIp, k -> new AtomicInteger(0))
                .incrementAndGet();

        var event = new SecurityEvent.AuthenticationFailure(Instant.now(), hashedIp, reason, method, count);
        dispatcher.dispatch(event);

        // Brute force detection
        if (count >= 5) {
            var suspiciousEvent = new SecurityEvent.SuspiciousPattern(
                    Instant.now(),
                    hashedIp,
                    "brute_force_attempt",
                    "Multiple authentication failures: " + count,
                    Math.min(1.0, count / 10.0));
            dispatcher.dispatch(suspiciousEvent);
        }
    }

    /**
     * Record an access denied event.
     *
     * @param clientIp the client IP address
     * @param serviceId the target service
     * @param path the requested path
     * @param reason the denial reason
     */
    public void recordAccessDenied(String clientIp, String serviceId, String path, String reason) {
        if (!enabled) {
            return;
        }

        var event = new SecurityEvent.AccessDenied(Instant.now(), hashIp(clientIp), serviceId, path, reason);
        dispatcher.dispatch(event);
    }

    /**
     * Record a session invalidation.
     *
     * @param clientIp the client IP address
     * @param sessionId the session ID (will be hashed)
     * @param userId the user ID
     * @param reason the invalidation reason
     */
    public void recordSessionInvalidation(String clientIp, String sessionId, String userId, String reason) {
        if (!enabled) {
            return;
        }

        var event = new SecurityEvent.SessionInvalidated(
                Instant.now(), hashIp(clientIp), hashSessionId(sessionId), userId, reason);
        dispatcher.dispatch(event);
    }

    /**
     * Reset tracking for a specific client.
     *
     * <p>Call this when a client is blocked or after a cooldown period.
     *
     * @param clientIp the client IP address
     */
    public void resetClient(String clientIp) {
        var hashedIp = hashIp(clientIp);
        clientRequestCounts.remove(hashedIp);
        clientErrorCounts.remove(hashedIp);
        authFailureCounts.remove(hashedIp);
    }

    private void checkForAnomalies(String clientIp, String serviceId, long requestCount) {
        var securityConfig = config.security();
        var threshold = securityConfig.rateLimitThreshold();
        var hashedIp = hashIp(clientIp);

        // Rate limit check
        if (requestCount > threshold) {
            var event = new SecurityEvent.RateLimitExceeded(
                    Instant.now(), hashedIp, serviceId, (int) requestCount, threshold, (int)
                            securityConfig.rateLimitWindow().toSeconds());
            dispatcher.dispatch(event);
        }

        // DoS detection
        if (securityConfig.dosDetection().enabled()) {
            detectDosPatterns(hashedIp, serviceId, requestCount, threshold);
        }
    }

    private void detectDosPatterns(String hashedIp, String serviceId, long requestCount, int threshold) {
        var dosConfig = config.security().dosDetection();
        var spikeThreshold = dosConfig.spikeThreshold();

        // Sudden spike detection
        if (requestCount > threshold * spikeThreshold) {
            var event = new SecurityEvent.DosAttackDetected(
                    Instant.now(),
                    hashedIp,
                    "request_flood",
                    Map.of(
                            "request_count",
                            requestCount,
                            "threshold",
                            threshold,
                            "spike_factor",
                            requestCount / (double) threshold,
                            "service_id",
                            serviceId != null ? serviceId : "unknown"));
            dispatcher.dispatch(event);

            LOG.warnf(
                    "Potential DoS attack detected: Client %s exceeding rate limits by %.1fx",
                    hashedIp, requestCount / (double) threshold);
        }

        // High error rate detection
        var errorCounter = clientErrorCounts.get(hashedIp);
        if (errorCounter != null && requestCount > 0) {
            double errorRate = errorCounter.getCount() / (double) requestCount;
            if (errorRate > dosConfig.errorRateThreshold()) {
                var event = new SecurityEvent.SuspiciousPattern(
                        Instant.now(),
                        hashedIp,
                        "high_error_rate",
                        String.format(
                                "Error rate: %.2f (%d errors / %d requests)",
                                errorRate, errorCounter.getCount(), requestCount),
                        errorRate);
                dispatcher.dispatch(event);
            }
        }
    }

    private String hashIp(String ip) {
        if (ip == null) {
            return "unknown";
        }
        var hash = Integer.toHexString(ip.hashCode());
        return hash.substring(0, Math.min(8, hash.length()));
    }

    private String hashSessionId(String sessionId) {
        if (sessionId == null) {
            return "unknown";
        }
        var hash = Integer.toHexString(sessionId.hashCode());
        return hash.substring(0, Math.min(8, hash.length()));
    }

    /**
     * Simple sliding window counter for rate tracking.
     */
    static class SlidingWindowCounter {
        private final long windowMs;
        private final AtomicLong count = new AtomicLong(0);
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());

        SlidingWindowCounter(Duration window) {
            this.windowMs = window.toMillis();
        }

        void increment() {
            long now = System.currentTimeMillis();
            long start = windowStart.get();

            // Reset counter if window has passed
            if (now - start > windowMs) {
                if (windowStart.compareAndSet(start, now)) {
                    count.set(1);
                } else {
                    count.incrementAndGet();
                }
            } else {
                count.incrementAndGet();
            }
        }

        long getCount() {
            long now = System.currentTimeMillis();
            long start = windowStart.get();

            // Return 0 if window has passed (stale data)
            if (now - start > windowMs) {
                return 0;
            }
            return count.get();
        }
    }
}
