package aussie.core.model.ratelimit;

import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Rate limit configuration for a specific endpoint.
 *
 * <p>Endpoint-level rate limits override service-level and platform defaults
 * for matching requests.
 *
 * <p>All values are optional; when not specified, service or platform defaults apply.
 *
 * @param requestsPerWindow maximum requests per window (optional)
 * @param windowSeconds window duration in seconds (optional)
 * @param burstCapacity burst capacity for token bucket (optional)
 */
public record EndpointRateLimitConfig(
        @JsonProperty("requestsPerWindow") Optional<Long> requestsPerWindow,
        @JsonProperty("windowSeconds") Optional<Long> windowSeconds,
        @JsonProperty("burstCapacity") Optional<Long> burstCapacity) {

    @JsonCreator
    public EndpointRateLimitConfig {
        requestsPerWindow = requestsPerWindow != null ? requestsPerWindow : Optional.empty();
        windowSeconds = windowSeconds != null ? windowSeconds : Optional.empty();
        burstCapacity = burstCapacity != null ? burstCapacity : Optional.empty();
    }

    /**
     * Create a rate limit config with all values specified.
     */
    public static EndpointRateLimitConfig of(long requestsPerWindow, long windowSeconds, long burstCapacity) {
        return new EndpointRateLimitConfig(
                Optional.of(requestsPerWindow), Optional.of(windowSeconds), Optional.of(burstCapacity));
    }

    /**
     * Create a rate limit config with requests and window specified.
     */
    public static EndpointRateLimitConfig of(long requestsPerWindow, long windowSeconds) {
        return new EndpointRateLimitConfig(
                Optional.of(requestsPerWindow), Optional.of(windowSeconds), Optional.empty());
    }

    /**
     * Create an empty configuration (use service/platform defaults).
     */
    public static EndpointRateLimitConfig defaults() {
        return new EndpointRateLimitConfig(Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Check if any rate limit values are configured.
     */
    public boolean hasConfiguration() {
        return requestsPerWindow.isPresent() || windowSeconds.isPresent() || burstCapacity.isPresent();
    }
}
