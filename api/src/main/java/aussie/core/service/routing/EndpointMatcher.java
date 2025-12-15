package aussie.core.service.routing;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.service.ServiceRegistration;

/**
 * Match request paths against configured endpoints.
 *
 * <p>Evaluates endpoints in order (first match wins) to find the appropriate
 * endpoint configuration including auth requirements.
 */
@ApplicationScoped
public class EndpointMatcher {

    private final GlobPatternMatcher patternMatcher;

    @Inject
    public EndpointMatcher(GlobPatternMatcher patternMatcher) {
        this.patternMatcher = patternMatcher;
    }

    /**
     * Find a matching endpoint for the given path and method.
     *
     * @param path    the request path (e.g., "/api/auth/login")
     * @param method  the HTTP method (e.g., "POST")
     * @param service the service registration containing endpoint configs
     * @return the matching endpoint config, or empty if no match
     */
    public Optional<EndpointConfig> match(String path, String method, ServiceRegistration service) {
        for (var endpoint : service.endpoints()) {
            if (matchesEndpoint(endpoint, path, method)) {
                return Optional.of(endpoint);
            }
        }
        return Optional.empty();
    }

    private boolean matchesEndpoint(EndpointConfig endpoint, String path, String method) {
        // Check if method matches
        if (!endpoint.methods().isEmpty()
                && !endpoint.methods().contains("*")
                && !endpoint.methods().contains(method.toUpperCase())) {
            return false;
        }

        // Check if path matches
        return patternMatcher.matches(endpoint.path(), path);
    }
}
