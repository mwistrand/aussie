package aussie.core.model;

/**
 * Types of rate limit keys used to identify different rate limiting contexts.
 *
 * <p>Each key type corresponds to a different rate limiting bucket:
 * <ul>
 *   <li>{@link #HTTP} - Standard HTTP request rate limiting</li>
 *   <li>{@link #WS_CONNECTION} - WebSocket connection establishment rate limiting</li>
 *   <li>{@link #WS_MESSAGE} - WebSocket message throughput rate limiting</li>
 * </ul>
 */
public enum RateLimitKeyType {

    /**
     * HTTP request rate limiting.
     *
     * <p>Key format: {@code aussie:ratelimit:http:{serviceId}:{endpointId}:{clientId}}
     */
    HTTP,

    /**
     * WebSocket connection establishment rate limiting.
     *
     * <p>Limits how frequently new WebSocket connections can be established.
     * Key format: {@code aussie:ratelimit:ws:conn:{serviceId}:{clientId}}
     */
    WS_CONNECTION,

    /**
     * WebSocket message throughput rate limiting.
     *
     * <p>Limits message throughput on established WebSocket connections.
     * Key format: {@code aussie:ratelimit:ws:msg:{serviceId}:{clientId}:{connectionId}}
     */
    WS_MESSAGE
}
