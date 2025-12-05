package aussie.core.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.EndpointConfig;
import aussie.core.model.RegistrationResult;
import aussie.core.model.RouteMatch;
import aussie.core.model.ServiceRegistration;
import aussie.core.model.ValidationResult;
import aussie.core.port.out.ConfigurationCache;
import aussie.core.port.out.ServiceRegistrationRepository;

/**
 * Service registry coordinating service registrations and route matching.
 *
 * <p>Uses repository port for persistence. Optionally uses cache if configured.
 * Maintains local compiled route patterns for fast request matching.
 *
 * <p>This service is responsible for validating all registration requests
 * before persisting them.
 */
@ApplicationScoped
public class ServiceRegistry {

    private final ServiceRegistrationRepository repository;
    private final ConfigurationCache cache;
    private final ServiceRegistrationValidator validator;

    // Local cache for compiled route patterns (always in-memory for fast matching)
    private final Map<String, CompiledRoute> compiledRoutes = new ConcurrentHashMap<>();

    @Inject
    public ServiceRegistry(
            ServiceRegistrationRepository repository,
            ConfigurationCache cache,
            ServiceRegistrationValidator validator) {
        this.repository = repository;
        this.cache = cache;
        this.validator = validator;
    }

    /**
     * Initialize route cache from persistent storage on startup.
     *
     * @return Uni completing when initialization is done
     */
    public Uni<Void> initialize() {
        return repository
                .findAll()
                .invoke(registrations -> {
                    for (ServiceRegistration registration : registrations) {
                        compileAndCacheRoutes(registration);
                    }
                })
                .replaceWithVoid();
    }

    /**
     * Register a new service.
     *
     * <p>Validates the registration against gateway policies before persisting.
     *
     * @param service The service registration to save
     * @return Uni with the registration result (success or failure with reason)
     */
    public Uni<RegistrationResult> register(ServiceRegistration service) {
        // Validate against gateway policies
        ValidationResult validationResult = validator.validate(service);
        if (validationResult instanceof ValidationResult.Invalid invalid) {
            return Uni.createFrom().item(RegistrationResult.failure(invalid.reason(), invalid.suggestedStatusCode()));
        }

        return repository
                .save(service)
                .invoke(() -> compileAndCacheRoutes(service))
                .call(() -> cache.put(service))
                .map(v -> RegistrationResult.success(service));
    }

    /**
     * Unregister a service by ID.
     *
     * @param serviceId The service ID to remove
     * @return Uni with true if the service was removed
     */
    public Uni<Boolean> unregister(String serviceId) {
        return repository.findById(serviceId).flatMap(opt -> {
            opt.ifPresent(this::removeCompiledRoutes);
            return cache.invalidate(serviceId).chain(() -> repository.delete(serviceId));
        });
    }

    /**
     * Get a service by ID.
     *
     * @param serviceId The service ID to find
     * @return Uni with Optional containing the service if found
     */
    public Uni<Optional<ServiceRegistration>> getService(String serviceId) {
        // Try cache first, then fall back to repository
        return cache.get(serviceId).flatMap(cached -> {
            if (cached.isPresent()) {
                return Uni.createFrom().item(cached);
            }
            return repository.findById(serviceId).call(opt -> opt.map(cache::put)
                    .orElse(Uni.createFrom().voidItem()));
        });
    }

    /**
     * Get all registered services.
     *
     * @return Uni with list of all services
     */
    public Uni<List<ServiceRegistration>> getAllServices() {
        return repository.findAll();
    }

    /**
     * Find a route matching the given path and method.
     *
     * <p>This is a synchronous operation using local compiled routes for performance.
     *
     * @param path The request path
     * @param method The HTTP method
     * @return Optional containing the route match if found
     */
    public Optional<RouteMatch> findRoute(String path, String method) {
        var normalizedPath = normalizePath(path);
        var upperMethod = method.toUpperCase();

        for (var route : compiledRoutes.values()) {
            if (!route.endpoint().methods().contains(upperMethod)
                    && !route.endpoint().methods().contains("*")) {
                continue;
            }

            var matcher = route.pattern().matcher(normalizedPath);
            if (matcher.matches()) {
                var pathVariables = extractPathVariables(route.endpoint().path(), matcher);
                var targetPath = route.endpoint()
                        .pathRewrite()
                        .map(rewrite -> applyPathRewrite(rewrite, pathVariables, normalizedPath))
                        .orElse(normalizedPath);

                return Optional.of(new RouteMatch(route.service(), route.endpoint(), targetPath, pathVariables));
            }
        }

        return Optional.empty();
    }

    private void compileAndCacheRoutes(ServiceRegistration service) {
        for (var endpoint : service.endpoints()) {
            var routeKey = buildRouteKey(service.serviceId(), endpoint.path());
            var pattern = compilePathPattern(endpoint.path());
            compiledRoutes.put(routeKey, new CompiledRoute(service, endpoint, pattern));
        }
    }

    private void removeCompiledRoutes(ServiceRegistration service) {
        for (var endpoint : service.endpoints()) {
            var routeKey = buildRouteKey(service.serviceId(), endpoint.path());
            compiledRoutes.remove(routeKey);
        }
    }

    private String buildRouteKey(String serviceId, String path) {
        return serviceId + ":" + path;
    }

    private String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
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
        var variables = new ConcurrentHashMap<String, String>();
        var paramPattern = Pattern.compile("\\{([^/]+)\\}");
        var paramMatcher = paramPattern.matcher(pathTemplate);

        while (paramMatcher.find()) {
            var paramName = paramMatcher.group(1);
            try {
                var value = matcher.group(paramName);
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
        for (var entry : pathVariables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private record CompiledRoute(ServiceRegistration service, EndpointConfig endpoint, Pattern pattern) {}
}
