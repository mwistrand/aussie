package aussie.core.model;

import java.util.Optional;

/**
 * Rate limit configuration for a service.
 *
 * <p>Service-level rate limits apply to all endpoints in the service unless
 * overridden by endpoint-specific configuration.
 *
 * <p>All values are optional; when not specified, platform defaults apply.
 *
 * @param requestsPerWindow maximum requests per window (optional)
 * @param windowSeconds window duration in seconds (optional)
 * @param burstCapacity burst capacity for token bucket (optional)
 */
public record ServiceRateLimitConfig(
        Optional<Long> requestsPerWindow, Optional<Long> windowSeconds, Optional<Long> burstCapacity) {

    public ServiceRateLimitConfig {
        requestsPerWindow = requestsPerWindow != null ? requestsPerWindow : Optional.empty();
        windowSeconds = windowSeconds != null ? windowSeconds : Optional.empty();
        burstCapacity = burstCapacity != null ? burstCapacity : Optional.empty();
    }

    /**
     * Creates a rate limit config with all values specified.
     */
    public static ServiceRateLimitConfig of(long requestsPerWindow, long windowSeconds, long burstCapacity) {
        return new ServiceRateLimitConfig(
                Optional.of(requestsPerWindow), Optional.of(windowSeconds), Optional.of(burstCapacity));
    }

    /**
     * Creates a rate limit config with requests and window specified.
     */
    public static ServiceRateLimitConfig of(long requestsPerWindow, long windowSeconds) {
        return new ServiceRateLimitConfig(Optional.of(requestsPerWindow), Optional.of(windowSeconds), Optional.empty());
    }

    /**
     * Creates an empty configuration (use platform defaults).
     */
    public static ServiceRateLimitConfig defaults() {
        return new ServiceRateLimitConfig(Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Checks if any rate limit values are configured.
     */
    public boolean hasConfiguration() {
        return requestsPerWindow.isPresent() || windowSeconds.isPresent() || burstCapacity.isPresent();
    }
}
