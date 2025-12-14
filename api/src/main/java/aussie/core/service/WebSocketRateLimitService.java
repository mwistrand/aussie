package aussie.core.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.config.RateLimitingConfig;
import aussie.core.model.RateLimitDecision;
import aussie.core.model.RateLimitKey;
import aussie.core.port.out.RateLimiter;

/**
 * Service for WebSocket-specific rate limiting.
 *
 * <p>Provides dedicated rate limiting for:
 * <ul>
 *   <li>Connection establishment - limits how fast clients can open new connections</li>
 *   <li>Message throughput - limits messages per second per connection</li>
 * </ul>
 *
 * <p>Connection rate limiting is handled by {@code WebSocketRateLimitFilter}.
 * This service is primarily used for message rate limiting within established connections.
 *
 * <p>All operations are fully reactive and never block.
 */
@ApplicationScoped
public class WebSocketRateLimitService {

    /**
     * WebSocket close code for rate limiting (4429 mirrors HTTP 429).
     */
    public static final short WS_CLOSE_CODE_RATE_LIMITED = 4429;

    private final RateLimiter rateLimiter;
    private final RateLimitingConfig config;
    private final RateLimitResolver rateLimitResolver;
    private final ServiceRegistry serviceRegistry;

    @Inject
    public WebSocketRateLimitService(
            RateLimiter rateLimiter,
            RateLimitingConfig config,
            RateLimitResolver rateLimitResolver,
            ServiceRegistry serviceRegistry) {
        this.rateLimiter = rateLimiter;
        this.config = config;
        this.rateLimitResolver = rateLimitResolver;
        this.serviceRegistry = serviceRegistry;
    }

    /**
     * Check if a WebSocket message is allowed.
     *
     * @param serviceId    the target service ID
     * @param clientId     the client identifier
     * @param connectionId the unique connection/session ID
     * @return rate limit decision
     */
    public Uni<RateLimitDecision> checkMessageLimit(String serviceId, String clientId, String connectionId) {
        if (!isMessageRateLimitEnabled()) {
            return Uni.createFrom().item(RateLimitDecision.allow());
        }

        final var key = RateLimitKey.wsMessage(clientId, serviceId, connectionId);

        // Look up service to get service-specific rate limit config
        return serviceRegistry.getServiceForRateLimiting(serviceId).flatMap(service -> {
            final var limit = rateLimitResolver.resolveWebSocketMessageLimit(service);
            return rateLimiter.checkAndConsume(key, limit);
        });
    }

    /**
     * Clean up rate limit state for a closed connection.
     *
     * @param serviceId    the target service ID
     * @param clientId     the client identifier
     * @param connectionId the unique connection/session ID
     * @return completion signal
     */
    public Uni<Void> cleanupConnection(String serviceId, String clientId, String connectionId) {
        final var pattern = "ws_message:" + clientId + ":" + serviceId + ":" + connectionId;
        return rateLimiter.removeKeysMatching(pattern);
    }

    /**
     * Check if WebSocket rate limiting is enabled overall.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return config.enabled() && rateLimiter.isEnabled();
    }

    /**
     * Check if connection rate limiting is enabled.
     *
     * @return true if enabled
     */
    public boolean isConnectionRateLimitEnabled() {
        return isEnabled() && config.websocket().connection().enabled();
    }

    /**
     * Check if message rate limiting is enabled.
     *
     * @return true if enabled
     */
    public boolean isMessageRateLimitEnabled() {
        return isEnabled() && config.websocket().message().enabled();
    }
}
