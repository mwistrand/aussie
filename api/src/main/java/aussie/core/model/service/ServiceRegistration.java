package aussie.core.model.service;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aussie.core.model.auth.ServiceAccessConfig;
import aussie.core.model.auth.ServicePermissionPolicy;
import aussie.core.model.auth.VisibilityRule;
import aussie.core.model.common.CorsConfig;
import aussie.core.model.ratelimit.ServiceRateLimitConfig;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteMatch;

/**
 * Represents a registered backend service in the gateway.
 *
 * <p>A service registration defines how the gateway routes requests to a backend
 * service, including:
 * <ul>
 *   <li>The service identifier used in gateway paths</li>
 *   <li>The backend base URL to proxy requests to</li>
 *   <li>Endpoint configurations with visibility and authentication settings</li>
 *   <li>Rate limiting configuration (optional)</li>
 *   <li>CORS configuration (optional)</li>
 *   <li>Permission policies for service-level access control</li>
 * </ul>
 *
 * <p>Gateway paths follow the format {@code /{serviceId}/{path}}, where the service ID
 * determines which backend service handles the request.
 *
 * @param serviceId the unique identifier for this service (used in gateway paths)
 * @param displayName human-readable name for display purposes
 * @param baseUrl the backend service URL to proxy requests to
 * @param routePrefix the prefix used in gateway paths (defaults to /{serviceId})
 * @param defaultVisibility default visibility for endpoints without explicit config
 * @param defaultAuthRequired whether authentication is required by default
 * @param visibilityRules pattern-based visibility rules for endpoints
 * @param endpoints explicit endpoint configurations
 * @param accessConfig access control configuration for IP/client filtering
 * @param corsConfig CORS configuration for cross-origin requests
 * @param permissionPolicy permission policy for authorization
 * @param rateLimitConfig service-level rate limit configuration
 * @param version optimistic locking version for concurrent updates
 */
public record ServiceRegistration(
        String serviceId,
        String displayName,
        URI baseUrl,
        String routePrefix,
        EndpointVisibility defaultVisibility,
        boolean defaultAuthRequired,
        List<VisibilityRule> visibilityRules,
        List<EndpointConfig> endpoints,
        Optional<ServiceAccessConfig> accessConfig,
        Optional<CorsConfig> corsConfig,
        Optional<ServicePermissionPolicy> permissionPolicy,
        Optional<ServiceRateLimitConfig> rateLimitConfig,
        long version) {
    public ServiceRegistration {
        if (serviceId == null || serviceId.isBlank()) {
            throw new IllegalArgumentException("Service ID cannot be null or blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("Display name cannot be null or blank");
        }
        if (baseUrl == null) {
            throw new IllegalArgumentException("Base URL cannot be null");
        }
        if (routePrefix == null || routePrefix.isBlank()) {
            routePrefix = "/" + serviceId;
        }
        if (defaultVisibility == null) {
            defaultVisibility = EndpointVisibility.PRIVATE;
        }
        if (visibilityRules == null) {
            visibilityRules = List.of();
        }
        if (endpoints == null) {
            endpoints = List.of();
        }
        if (accessConfig == null) {
            accessConfig = Optional.empty();
        }
        if (corsConfig == null) {
            corsConfig = Optional.empty();
        }
        if (permissionPolicy == null) {
            permissionPolicy = Optional.empty();
        }
        if (rateLimitConfig == null) {
            rateLimitConfig = Optional.empty();
        }
        if (version < 0) {
            version = 1;
        }
    }

    /**
     * Creates a new ServiceRegistration with an incremented version.
     */
    public ServiceRegistration withIncrementedVersion() {
        return new ServiceRegistration(
                serviceId,
                displayName,
                baseUrl,
                routePrefix,
                defaultVisibility,
                defaultAuthRequired,
                visibilityRules,
                endpoints,
                accessConfig,
                corsConfig,
                permissionPolicy,
                rateLimitConfig,
                version + 1);
    }

    /**
     * Creates a new ServiceRegistration with the given permission policy.
     */
    public ServiceRegistration withPermissionPolicy(ServicePermissionPolicy policy) {
        return new ServiceRegistration(
                serviceId,
                displayName,
                baseUrl,
                routePrefix,
                defaultVisibility,
                defaultAuthRequired,
                visibilityRules,
                endpoints,
                accessConfig,
                corsConfig,
                Optional.ofNullable(policy),
                rateLimitConfig,
                version);
    }

    /**
     * Creates a new ServiceRegistration with the given rate limit config.
     */
    public ServiceRegistration withRateLimitConfig(ServiceRateLimitConfig config) {
        return new ServiceRegistration(
                serviceId,
                displayName,
                baseUrl,
                routePrefix,
                defaultVisibility,
                defaultAuthRequired,
                visibilityRules,
                endpoints,
                accessConfig,
                corsConfig,
                permissionPolicy,
                Optional.ofNullable(config),
                version);
    }

    /**
     * Finds a route matching the given path and method within this service's
     * endpoints.
     *
     * @param path   the request path to match
     * @param method the HTTP method
     * @return Optional containing the RouteMatch if found
     */
    public Optional<RouteMatch> findRoute(String path, String method) {
        final var normalizedPath = normalizePath(path);
        final var upperMethod = method.toUpperCase();

        for (final var endpoint : endpoints) {
            if (!endpoint.methods().contains(upperMethod) && !endpoint.methods().contains("*")) {
                continue;
            }

            final var pattern = compilePathPattern(endpoint.path());
            final var matcher = pattern.matcher(normalizedPath);
            if (matcher.matches()) {
                final var pathVariables = extractPathVariables(endpoint.path(), matcher);
                final var targetPath = endpoint.pathRewrite()
                        .map(rewrite -> applyPathRewrite(rewrite, pathVariables, normalizedPath))
                        .orElse(normalizedPath);

                return Optional.of(new RouteMatch(this, endpoint, targetPath, pathVariables));
            }
        }

        return Optional.empty();
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (!path.startsWith("/")) {
            return "/" + path;
        }
        return path;
    }

    private Pattern compilePathPattern(String pathTemplate) {
        // Convert path template with {param} placeholders to regex
        var regex = pathTemplate
                .replaceAll("\\{([^/]+)\\}", "(?<$1>[^/]+)")
                .replaceAll("\\*\\*", ".*")
                .replaceAll("(?<!\\.)\\*", "[^/]*");

        return Pattern.compile("^" + regex + "$");
    }

    private Map<String, String> extractPathVariables(String pathTemplate, Matcher matcher) {
        final var variables = new HashMap<String, String>();
        final var paramPattern = Pattern.compile("\\{([^/]+)\\}");
        final var paramMatcher = paramPattern.matcher(pathTemplate);

        while (paramMatcher.find()) {
            final var paramName = paramMatcher.group(1);
            try {
                final var value = matcher.group(paramName);
                if (value != null) {
                    variables.put(paramName, value);
                }
            } catch (IllegalArgumentException e) {
                // Group not found, skip
            }
        }

        return variables;
    }

    private String applyPathRewrite(String rewritePattern, Map<String, String> pathVariables, String originalPath) {
        var result = rewritePattern;
        for (final var entry : pathVariables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    public static Builder builder(String serviceId) {
        return new Builder(serviceId);
    }

    public static class Builder {
        private final String serviceId;
        private String displayName;
        private URI baseUrl;
        private String routePrefix;
        private EndpointVisibility defaultVisibility;
        private boolean defaultAuthRequired = true;
        private List<VisibilityRule> visibilityRules = List.of();
        private List<EndpointConfig> endpoints = List.of();
        private ServiceAccessConfig accessConfig;
        private CorsConfig corsConfig;
        private ServicePermissionPolicy permissionPolicy;
        private ServiceRateLimitConfig rateLimitConfig;
        private long version = 1;

        private Builder(String serviceId) {
            this.serviceId = serviceId;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder baseUrl(URI baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = URI.create(baseUrl);
            return this;
        }

        public Builder routePrefix(String routePrefix) {
            this.routePrefix = routePrefix;
            return this;
        }

        public Builder defaultVisibility(EndpointVisibility defaultVisibility) {
            this.defaultVisibility = defaultVisibility;
            return this;
        }

        public Builder defaultAuthRequired(boolean defaultAuthRequired) {
            this.defaultAuthRequired = defaultAuthRequired;
            return this;
        }

        public Builder visibilityRules(List<VisibilityRule> visibilityRules) {
            this.visibilityRules = visibilityRules;
            return this;
        }

        public Builder endpoints(List<EndpointConfig> endpoints) {
            this.endpoints = endpoints;
            return this;
        }

        public Builder accessConfig(ServiceAccessConfig accessConfig) {
            this.accessConfig = accessConfig;
            return this;
        }

        public Builder corsConfig(CorsConfig corsConfig) {
            this.corsConfig = corsConfig;
            return this;
        }

        public Builder permissionPolicy(ServicePermissionPolicy permissionPolicy) {
            this.permissionPolicy = permissionPolicy;
            return this;
        }

        public Builder rateLimitConfig(ServiceRateLimitConfig rateLimitConfig) {
            this.rateLimitConfig = rateLimitConfig;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public ServiceRegistration build() {
            return new ServiceRegistration(
                    serviceId,
                    displayName != null ? displayName : serviceId,
                    baseUrl,
                    routePrefix,
                    defaultVisibility,
                    defaultAuthRequired,
                    visibilityRules,
                    endpoints,
                    Optional.ofNullable(accessConfig),
                    Optional.ofNullable(corsConfig),
                    Optional.ofNullable(permissionPolicy),
                    Optional.ofNullable(rateLimitConfig),
                    version);
        }
    }
}
