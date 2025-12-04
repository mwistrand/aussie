package aussie.core.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.model.EndpointVisibility;
import aussie.core.model.ServiceRegistration;

/**
 * Resolves the visibility for a given path and method based on service configuration.
 * Evaluates visibility rules in order (first match wins) and falls back to defaultVisibility.
 */
@ApplicationScoped
public class VisibilityResolver {

    private final GlobPatternMatcher patternMatcher;

    @Inject
    public VisibilityResolver(GlobPatternMatcher patternMatcher) {
        this.patternMatcher = patternMatcher;
    }

    /**
     * Resolves the visibility for a request path and method.
     *
     * @param path the request path (e.g., "/api/users/123")
     * @param method the HTTP method (e.g., "GET", "POST")
     * @param service the service registration containing visibility rules
     * @return the resolved visibility (PUBLIC or PRIVATE)
     */
    public EndpointVisibility resolve(String path, String method, ServiceRegistration service) {
        // Check visibility rules in order (first match wins)
        for (var rule : service.visibilityRules()) {
            if (matchesRule(rule.pattern(), rule.appliesToMethod(method), path)) {
                return rule.visibility();
            }
        }

        // Fall back to default visibility
        return service.defaultVisibility();
    }

    private boolean matchesRule(String pattern, boolean methodMatches, String path) {
        if (!methodMatches) {
            return false;
        }
        return patternMatcher.matches(pattern, path);
    }
}
