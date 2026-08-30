package aussie.core.model.timeout;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Timeout configuration for a service.
 *
 * <p>Service-level timeouts apply to all endpoints in the service unless
 * overridden by endpoint-specific configuration.
 *
 * <p>All values are optional; when not specified, platform defaults apply.
 * Values cannot exceed the platform-configured global maximum.
 *
 * @param requestTimeout maximum time to wait for a response from the upstream service
 */
public record ServiceTimeoutConfig(
        @JsonProperty("requestTimeout") Optional<Duration> requestTimeout) {

    @JsonCreator
    public ServiceTimeoutConfig {
        requestTimeout = requestTimeout != null ? requestTimeout : Optional.empty();

        requestTimeout.ifPresent(timeout -> {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("Request timeout must be positive, got: " + timeout);
            }
        });
    }

    /**
     * Create a timeout config with the given request timeout.
     *
     * @param requestTimeout the request timeout duration
     * @return a new ServiceTimeoutConfig
     */
    public static ServiceTimeoutConfig of(Duration requestTimeout) {
        return new ServiceTimeoutConfig(Optional.of(requestTimeout));
    }

    /**
     * Create an empty configuration (use platform defaults).
     *
     * @return a config with no timeout specified
     */
    public static ServiceTimeoutConfig defaults() {
        return new ServiceTimeoutConfig(Optional.empty());
    }

    /**
     * Check if a request timeout is configured.
     *
     * @return true if requestTimeout is present
     */
    public boolean hasConfiguration() {
        return requestTimeout.isPresent();
    }
}
