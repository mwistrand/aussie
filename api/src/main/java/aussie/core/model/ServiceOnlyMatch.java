package aussie.core.model;

import java.util.Optional;

/**
 * Represents a route lookup result where only the service was matched, with no
 * specific endpoint.
 *
 * <p>
 * This is used for pass-through requests where a service is identified but
 * no explicit endpoint configuration matches the request path.
 *
 * <p>
 * All configuration values (visibility, authRequired, rateLimitConfig) are
 * resolved from the service's defaults.
 */
public record ServiceOnlyMatch(ServiceRegistration service) implements RouteLookupResult {

    public ServiceOnlyMatch {
        if (service == null) {
            throw new IllegalArgumentException("Service cannot be null");
        }
    }

    @Override
    public Optional<EndpointConfig> endpoint() {
        return Optional.empty();
    }
}
