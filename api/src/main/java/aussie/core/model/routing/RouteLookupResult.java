package aussie.core.model.routing;

import java.util.Optional;

import aussie.core.model.ratelimit.ServiceRateLimitConfig;
import aussie.core.model.service.ServiceRegistration;

/**
 * Result of looking up a route in the service registry.
 *
 * <p>
 * This sealed interface represents two possible outcomes:
 * <ul>
 * <li>{@link RouteMatch} - A specific endpoint was matched</li>
 * <li>{@link ServiceOnlyMatch} - Only the service was matched (no specific
 * endpoint)</li>
 * </ul>
 *
 * <p>
 * Both outcomes provide access to the service and resolved configuration values
 * (visibility, authRequired, rateLimitConfig) that account for the endpoint
 * when present, or fall back to service defaults otherwise.
 */
public sealed interface RouteLookupResult permits RouteMatch, ServiceOnlyMatch {

    /**
     * Return the matched service registration.
     *
     * @return the service registration
     */
    ServiceRegistration service();

    /**
     * Return the matched endpoint, if any.
     *
     * @return the endpoint config if a specific route was matched, empty otherwise
     */
    Optional<EndpointConfig> endpoint();

    /**
     * Return the effective visibility for this route.
     *
     * <p>
     * If a specific endpoint was matched, returns the endpoint's visibility.
     * Otherwise, returns the service's default visibility.
     *
     * @return the effective visibility
     */
    default EndpointVisibility visibility() {
        return endpoint().map(EndpointConfig::visibility).orElseGet(() -> service()
                .defaultVisibility());
    }

    /**
     * Return whether authentication is required for this route.
     *
     * <p>
     * If a specific endpoint was matched, returns the endpoint's authRequired
     * setting.
     * Otherwise, returns the service's default authRequired setting.
     *
     * @return true if authentication is required
     */
    default boolean authRequired() {
        return endpoint().map(EndpointConfig::authRequired).orElseGet(() -> service()
                .defaultAuthRequired());
    }

    /**
     * Return the effective rate limit configuration for this route.
     *
     * <p>
     * If a specific endpoint was matched and has a rate limit config, returns that.
     * Otherwise, returns the service's rate limit config (if any).
     *
     * @return the effective rate limit config, or empty if none configured
     */
    default Optional<ServiceRateLimitConfig> rateLimitConfig() {
        return endpoint()
                .flatMap(EndpointConfig::rateLimitConfig)
                .map(erc ->
                        new ServiceRateLimitConfig(erc.requestsPerWindow(), erc.windowSeconds(), erc.burstCapacity()))
                .or(() -> service().rateLimitConfig());
    }
}
