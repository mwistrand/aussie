package aussie.core.model;

import java.util.Optional;

/**
 * WebSocket rate limit configuration for a service.
 *
 * <p>Service-level WebSocket rate limits override platform defaults but are
 * still capped at the platform maximum.
 *
 * @param connection connection establishment rate limits
 * @param message message throughput rate limits
 */
public record ServiceWebSocketRateLimitConfig(Optional<RateLimitValues> connection, Optional<RateLimitValues> message) {

    public ServiceWebSocketRateLimitConfig {
        connection = connection != null ? connection : Optional.empty();
        message = message != null ? message : Optional.empty();
    }

    /**
     * Creates a WebSocket config with both connection and message limits.
     */
    public static ServiceWebSocketRateLimitConfig of(RateLimitValues connection, RateLimitValues message) {
        return new ServiceWebSocketRateLimitConfig(Optional.ofNullable(connection), Optional.ofNullable(message));
    }

    /**
     * Creates an empty configuration (use platform defaults).
     */
    public static ServiceWebSocketRateLimitConfig defaults() {
        return new ServiceWebSocketRateLimitConfig(Optional.empty(), Optional.empty());
    }

    /**
     * Checks if any WebSocket rate limit values are configured.
     */
    public boolean hasConfiguration() {
        return connection.isPresent() || message.isPresent();
    }

    /**
     * Rate limit values for a specific context (connection or message).
     *
     * @param requestsPerWindow maximum requests per window
     * @param windowSeconds window duration in seconds
     * @param burstCapacity burst capacity for token bucket
     */
    public record RateLimitValues(
            Optional<Long> requestsPerWindow, Optional<Long> windowSeconds, Optional<Long> burstCapacity) {

        public RateLimitValues {
            requestsPerWindow = requestsPerWindow != null ? requestsPerWindow : Optional.empty();
            windowSeconds = windowSeconds != null ? windowSeconds : Optional.empty();
            burstCapacity = burstCapacity != null ? burstCapacity : Optional.empty();
        }

        /**
         * Creates rate limit values with all fields specified.
         */
        public static RateLimitValues of(long requestsPerWindow, long windowSeconds, long burstCapacity) {
            return new RateLimitValues(
                    Optional.of(requestsPerWindow), Optional.of(windowSeconds), Optional.of(burstCapacity));
        }

        /**
         * Creates rate limit values with requests and window specified.
         */
        public static RateLimitValues of(long requestsPerWindow, long windowSeconds) {
            return new RateLimitValues(Optional.of(requestsPerWindow), Optional.of(windowSeconds), Optional.empty());
        }

        /**
         * Checks if any values are configured.
         */
        public boolean hasConfiguration() {
            return requestsPerWindow.isPresent() || windowSeconds.isPresent() || burstCapacity.isPresent();
        }
    }
}
