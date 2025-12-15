package aussie.core.model.ratelimit;

import java.util.Objects;
import java.util.Optional;

/**
 * Identifies a rate limit bucket for a specific client, service, and endpoint combination.
 *
 * <p>Rate limit keys are used to look up and store rate limit state. The key format
 * varies by {@link RateLimitKeyType}:
 * <ul>
 *   <li>HTTP: {@code aussie:ratelimit:http:{serviceId}:{endpointId}:{clientId}}</li>
 *   <li>WS_CONNECTION: {@code aussie:ratelimit:ws:conn:{serviceId}:{clientId}}</li>
 *   <li>WS_MESSAGE: {@code aussie:ratelimit:ws:msg:{serviceId}:{clientId}:{connectionId}}</li>
 * </ul>
 *
 * @param keyType the type of rate limit (HTTP, WS_CONNECTION, WS_MESSAGE)
 * @param clientId the client identifier (session ID, auth token hash, API key ID, or IP)
 * @param serviceId the target service identifier
 * @param endpointId the endpoint identifier (for HTTP) or connection ID (for WS_MESSAGE)
 */
public record RateLimitKey(RateLimitKeyType keyType, String clientId, String serviceId, Optional<String> endpointId) {

    /**
     * Creates a rate limit key with validation.
     */
    public RateLimitKey {
        Objects.requireNonNull(keyType, "keyType must not be null");
        Objects.requireNonNull(clientId, "clientId must not be null");
        Objects.requireNonNull(serviceId, "serviceId must not be null");
        endpointId = Objects.requireNonNullElse(endpointId, Optional.empty());
    }

    /**
     * Creates an HTTP rate limit key.
     *
     * @param clientId the client identifier
     * @param serviceId the service identifier
     * @param endpointId the endpoint identifier (may be null)
     * @return the rate limit key
     */
    public static RateLimitKey http(String clientId, String serviceId, String endpointId) {
        return new RateLimitKey(RateLimitKeyType.HTTP, clientId, serviceId, Optional.ofNullable(endpointId));
    }

    /**
     * Creates a WebSocket connection rate limit key.
     *
     * @param clientId the client identifier
     * @param serviceId the service identifier
     * @return the rate limit key
     */
    public static RateLimitKey wsConnection(String clientId, String serviceId) {
        return new RateLimitKey(RateLimitKeyType.WS_CONNECTION, clientId, serviceId, Optional.empty());
    }

    /**
     * Creates a WebSocket message rate limit key.
     *
     * @param clientId the client identifier
     * @param serviceId the service identifier
     * @param connectionId the WebSocket connection identifier
     * @return the rate limit key
     */
    public static RateLimitKey wsMessage(String clientId, String serviceId, String connectionId) {
        return new RateLimitKey(RateLimitKeyType.WS_MESSAGE, clientId, serviceId, Optional.of(connectionId));
    }

    /**
     * Converts this key to a cache key string.
     *
     * @return the cache key string
     */
    public String toCacheKey() {
        return switch (keyType) {
            case HTTP -> buildHttpKey();
            case WS_CONNECTION -> buildWsConnectionKey();
            case WS_MESSAGE -> buildWsMessageKey();
        };
    }

    private String buildHttpKey() {
        final var base = "aussie:ratelimit:http:" + serviceId;
        return endpointId.map(ep -> base + ":" + ep + ":" + clientId).orElse(base + ":*:" + clientId);
    }

    private String buildWsConnectionKey() {
        return "aussie:ratelimit:ws:conn:" + serviceId + ":" + clientId;
    }

    private String buildWsMessageKey() {
        return "aussie:ratelimit:ws:msg:" + serviceId + ":" + clientId + ":" + endpointId.orElse("unknown");
    }
}
