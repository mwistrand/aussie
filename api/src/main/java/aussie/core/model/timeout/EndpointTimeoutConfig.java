package aussie.core.model.timeout;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Timeout configuration for a specific endpoint.
 *
 * <p>Endpoint-level timeouts override service-level and platform defaults
 * for matching requests.
 *
 * <p>All values are optional; when not specified, service or platform defaults apply.
 * Values cannot exceed the platform-configured global maximum.
 *
 * @param requestTimeout maximum time to wait for a response from the upstream service
 */
public record EndpointTimeoutConfig(@JsonProperty("requestTimeout") Optional<Duration> requestTimeout) {

    @JsonCreator
    public EndpointTimeoutConfig {
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
     * @return a new EndpointTimeoutConfig
     */
    public static EndpointTimeoutConfig of(Duration requestTimeout) {
        return new EndpointTimeoutConfig(Optional.of(requestTimeout));
    }

    /**
     * Create an empty configuration (use service/platform defaults).
     *
     * @return a config with no timeout specified
     */
    public static EndpointTimeoutConfig defaults() {
        return new EndpointTimeoutConfig(Optional.empty());
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
