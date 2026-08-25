package aussie.adapter.out.telemetry;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jboss.logging.Logger;

import aussie.common.context.ClientContext;
import aussie.core.port.out.SecurityMonitoring;
import aussie.core.util.SecureHash;
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

    private final SecurityEventDispatcher dispatcher;
    private final boolean enabled;
    private final Duration rateLimitWindow;
    private final int rateLimitThreshold;
    private final boolean dosDetectionEnabled;
    private final double dosSpikeThreshold;
    private final double dosErrorRateThreshold;

    // Per-client request tracking
    private final Cache<String, ClientCounters> clientCounters;

    @Inject
    public SecurityMonitor(TelemetryConfig config, SecurityEventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        final var security = config == null ? null : config.security();
        final var dos = security == null ? null : security.dosDetection();
        this.enabled = config != null && config.enabled() && security != null && security.enabled();
        this.rateLimitWindow = security == null ? Duration.ofMinutes(1) : security.rateLimitWindow();
        this.rateLimitThreshold = security == null ? 1_000 : security.rateLimitThreshold();
        this.dosDetectionEnabled = dos != null && dos.enabled();
        this.dosSpikeThreshold = dos == null ? 5.0 : dos.spikeThreshold();
        this.dosErrorRateThreshold = dos == null ? 0.5 : dos.errorRateThreshold();
        final var maxClients =
                security == null || security.maxTrackedClients() <= 0 ? 10_000 : security.maxTrackedClients();
        final var ttl = security == null
                        || security.clientTrackingTtl() == null
                        || security.clientTrackingTtl().isNegative()
                        || security.clientTrackingTtl().isZero()
                ? Duration.ofMinutes(10)
                : security.clientTrackingTtl();
        clientCounters = Caffeine.newBuilder()
                .maximumSize(maxClients)
                .expireAfterAccess(ttl)
                .build();
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

        final var hashedIp = hashIp(clientIp);
        final var counters = clientCounters.get(hashedIp, ignored -> new ClientCounters(rateLimitWindow));
        counters.requests().increment();

        if (isError) {
            counters.errors().increment();
        }

        checkForAnomalies(hashedIp, serviceId, counters);
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

        final var hashedIp = hashIp(clientIp);
        final var counters = clientCounters.get(hashedIp, ignored -> new ClientCounters(rateLimitWindow));
        final var count = counters.authFailures().incrementAndGet();

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

    @Override
    public void recordAccessDenied(
            ClientContext clientContext, String serviceId, String routePattern, String reason, long policyVersion) {
        if (!enabled) {
            return;
        }

        final var source = clientContext.forwardedClientIp() != null
                ? "forwarded"
                : clientContext.socketIp() != null ? "socket" : "unknown";
        final var peerTrust = clientContext.socketIp() == null
                ? "peer-unknown"
                : clientContext.trustForwardingHeaders() ? "peer-trusted" : "peer-untrusted";
        final var trustPath = java.util.stream.Stream.concat(
                        clientContext.forwardingChain().stream().map(hop -> hop.trusted() ? "trusted" : "untrusted"),
                        java.util.stream.Stream.of(peerTrust))
                .collect(java.util.stream.Collectors.joining(">"));
        dispatcher.dispatch(new SecurityEvent.AccessDenied(
                Instant.now(),
                hashIp(clientContext.resolvedIp()),
                serviceId,
                routePattern,
                reason,
                source,
                hashIp(clientContext.socketIp()),
                trustPath,
                policyVersion));
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
        clientCounters.invalidate(hashIp(clientIp));
    }

    private void checkForAnomalies(String hashedIp, String serviceId, ClientCounters counters) {
        final var requestCount = counters.requests().getCount();

        // Rate limit check
        if (requestCount > rateLimitThreshold) {
            var event = new SecurityEvent.RateLimitExceeded(
                    Instant.now(), hashedIp, serviceId, (int) requestCount, rateLimitThreshold, (int)
                            rateLimitWindow.toSeconds());
            dispatcher.dispatch(event);
        }

        // DoS detection
        if (dosDetectionEnabled) {
            detectDosPatterns(hashedIp, serviceId, counters.errors(), requestCount);
        }
    }

    private void detectDosPatterns(
            String hashedIp, String serviceId, SlidingWindowCounter errorCounter, long requestCount) {
        // Sudden spike detection
        if (requestCount > rateLimitThreshold * dosSpikeThreshold) {
            var event = new SecurityEvent.DosAttackDetected(
                    Instant.now(),
                    hashedIp,
                    "request_flood",
                    Map.of(
                            "request_count",
                            requestCount,
                            "threshold",
                            rateLimitThreshold,
                            "spike_factor",
                            requestCount / (double) rateLimitThreshold,
                            "service_id",
                            serviceId != null ? serviceId : "unknown"));
            dispatcher.dispatch(event);

            LOG.warnf(
                    "Potential DoS attack detected: Client %s exceeding rate limits by %.1fx",
                    hashedIp, requestCount / (double) rateLimitThreshold);
        }

        // High error rate detection
        if (requestCount > 0) {
            double errorRate = errorCounter.getCount() / (double) requestCount;
            if (errorRate > dosErrorRateThreshold) {
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

    private record ClientCounters(
            SlidingWindowCounter requests, SlidingWindowCounter errors, AtomicInteger authFailures) {

        private ClientCounters(Duration window) {
            this(new SlidingWindowCounter(window), new SlidingWindowCounter(window), new AtomicInteger());
        }
    }

    private String hashIp(String ip) {
        if (ip == null || "unknown".equals(ip)) {
            return "unknown";
        }
        return SecureHash.truncatedSha256(ip, 16);
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
