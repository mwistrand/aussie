package aussie.core.model.routing;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import aussie.core.model.service.ServiceRegistration;

/**
 * Represents a route lookup result where a specific endpoint was matched.
 *
 * <p>
 * Contains the matched service, endpoint, resolved target path, and any path
 * variables extracted from the request path.
 *
 * <p>
 * Configuration values (visibility, authRequired, rateLimitConfig) are resolved
 * from the endpoint when available, falling back to service defaults otherwise.
 */
public record RouteMatch(
        ServiceRegistration service,
        EndpointConfig endpointConfig,
        String targetPath,
        Map<String, String> pathVariables)
        implements RouteLookupResult {
    public RouteMatch {
        if (service == null) {
            throw new IllegalArgumentException("Service cannot be null");
        }
        if (endpointConfig == null) {
            throw new IllegalArgumentException("Endpoint cannot be null");
        }
        if (targetPath == null) {
            targetPath = "";
        }
        if (pathVariables == null) {
            pathVariables = Map.of();
        }
    }

    @Override
    public Optional<EndpointConfig> endpoint() {
        return Optional.of(endpointConfig);
    }

    /**
     * Return the full target URI for this route match without query parameters.
     */
    public URI targetUri() {
        return targetUri(null);
    }

    /**
     * Return the full target URI for this route match with optional query string.
     *
     * @param query the query string to append (without leading '?'), or null
     * @return the complete target URI
     */
    public URI targetUri(String query) {
        var base = service.baseUrl().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        var path = targetPath.startsWith("/") ? targetPath : "/" + targetPath;
        if (query != null && !query.isEmpty()) {
            return URI.create(base + path + "?" + query);
        }
        return URI.create(base + path);
    }
}
