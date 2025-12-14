package aussie.core.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.EndpointConfig;
import aussie.core.model.Permission;
import aussie.core.model.RegistrationResult;
import aussie.core.model.RouteLookupResult;
import aussie.core.model.ServiceOnlyMatch;
import aussie.core.model.ServicePermissionPolicy;
import aussie.core.model.ServiceRegistration;
import aussie.core.model.ValidationResult;
import aussie.core.port.out.ConfigurationCache;
import aussie.core.port.out.ServiceRegistrationRepository;

/**
 * Service registry coordinating service registrations and route matching.
 *
 * <p>
 * Uses repository port for persistence. Optionally uses cache if configured.
 * Maintains local compiled route patterns for fast request matching.
 *
 * <p>
 * This service is responsible for:
 * <ul>
 * <li>Validating all registration requests before persisting</li>
 * <li>Enforcing authorization for service operations</li>
 * <li>Detecting permission policy changes that require elevated privileges</li>
 * </ul>
 */
@ApplicationScoped
public class ServiceRegistry {

    private final ServiceRegistrationRepository repository;
    private final ConfigurationCache cache;
    private final ServiceRegistrationValidator validator;
    private final ServiceAuthorizationService authService;

    // Local cache for compiled route patterns (always in-memory for fast matching)
    private final Map<String, CompiledRoute> compiledRoutes = new ConcurrentHashMap<>();

    @Inject
    public ServiceRegistry(
            ServiceRegistrationRepository repository,
            ConfigurationCache cache,
            ServiceRegistrationValidator validator,
            ServiceAuthorizationService authService) {
        this.repository = repository;
        this.cache = cache;
        this.validator = validator;
        this.authService = authService;
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
     * Register a new service or update an existing one.
     *
     * <p>
     * Validates the registration against gateway policies before persisting.
     * For new services, the version must be 1. For updates, the version must be
     * exactly the current stored version plus one (optimistic locking).
     *
     * <p>
     * This method does NOT enforce authorization. Use
     * {@link #register(ServiceRegistration, Set)} for authorized registration.
     *
     * @param service The service registration to save
     * @return Uni with the registration result (success or failure with reason)
     */
    public Uni<RegistrationResult> register(ServiceRegistration service) {
        return register(service, null);
    }

    /**
     * Register a new service or update an existing one with authorization.
     *
     * <p>
     * Validates the registration against gateway policies before persisting.
     * For new services, the version must be 1. For updates, the version must be
     * exactly the current stored version plus one (optimistic locking).
     *
     * <p>
     * Authorization is enforced based on the operation:
     * <ul>
     * <li>New service: requires service.config.create permission</li>
     * <li>Update: requires service.config.update permission on the existing
     * service</li>
     * <li>Permission policy change: requires service.permissions.write
     * permission</li>
     * </ul>
     *
     * @param service The service registration to save
     * @param claims  The claims from the authenticated principal (null to skip
     *                authorization)
     * @return Uni with the registration result (success or failure with reason)
     */
    public Uni<RegistrationResult> register(ServiceRegistration service, Set<String> claims) {
        // Validate against gateway policies
        ValidationResult validationResult = validator.validate(service);
        if (validationResult instanceof ValidationResult.Invalid invalid) {
            return Uni.createFrom().item(RegistrationResult.failure(invalid.reason(), invalid.suggestedStatusCode()));
        }

        // Check version constraint and authorization
        return repository.findById(service.serviceId()).flatMap(existingOpt -> {
            if (existingOpt.isEmpty()) {
                // New service: version must be 1
                if (service.version() != 1L) {
                    return Uni.createFrom()
                            .item(RegistrationResult.failure(
                                    "New service registration must have version 1, got " + service.version(), 409));
                }

                // Check create authorization if claims provided
                if (claims != null && !authService.canCreateService(claims)) {
                    return Uni.createFrom()
                            .item(RegistrationResult.failure(
                                    "Not authorized to create service: " + service.serviceId(), 403));
                }
            } else {
                var existing = existingOpt.get();

                // Update: version must be current + 1
                long currentVersion = existing.version();
                long expectedVersion = currentVersion + 1;
                if (service.version() != expectedVersion) {
                    return Uni.createFrom()
                            .item(RegistrationResult.failure(
                                    "Version conflict: expected version " + expectedVersion + " (current is "
                                            + currentVersion + "), got " + service.version(),
                                    409));
                }

                // Check update authorization if claims provided
                if (claims != null && !authService.isAuthorizedForService(existing, Permission.CONFIG_UPDATE, claims)) {
                    return Uni.createFrom()
                            .item(RegistrationResult.failure(
                                    "Not authorized to update service: " + service.serviceId(), 403));
                }

                // Check if permission policy is changing (not just present)
                if (claims != null && hasPermissionPolicyChanged(existing, service)) {
                    if (!authService.isAuthorizedForService(existing, Permission.PERMISSIONS_WRITE, claims)) {
                        return Uni.createFrom()
                                .item(RegistrationResult.failure(
                                        "Not authorized to update permissions for service: " + service.serviceId(),
                                        403));
                    }
                }
            }

            return repository
                    .save(service)
                    .invoke(() -> compileAndCacheRoutes(service))
                    .call(() -> cache.put(service))
                    .map(v -> RegistrationResult.success(service));
        });
    }

    /**
     * Checks if the permission policy has changed between existing and new service.
     *
     * @param existing The existing service registration
     * @param updated  The updated service registration
     * @return true if the permission policy has changed
     */
    private boolean hasPermissionPolicyChanged(ServiceRegistration existing, ServiceRegistration updated) {
        Optional<ServicePermissionPolicy> existingPolicy = existing.permissionPolicy();
        Optional<ServicePermissionPolicy> updatedPolicy = updated.permissionPolicy();

        // Both empty = no change
        if (existingPolicy.isEmpty() && updatedPolicy.isEmpty()) {
            return false;
        }

        // One empty, one present = change
        if (existingPolicy.isEmpty() || updatedPolicy.isEmpty()) {
            return true;
        }

        // Both present: compare the policies
        return !Objects.equals(existingPolicy.get(), updatedPolicy.get());
    }

    /**
     * Unregister a service by ID.
     *
     * <p>
     * This method does NOT enforce authorization. Use
     * {@link #unregisterAuthorized(String, Set)} for authorized unregistration.
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
     * Unregister a service by ID with authorization.
     *
     * @param serviceId The service ID to remove
     * @param claims    The claims from the authenticated principal
     * @return Uni with the unregistration result
     */
    public Uni<RegistrationResult> unregisterAuthorized(String serviceId, Set<String> claims) {
        return repository.findById(serviceId).flatMap(opt -> {
            if (opt.isEmpty()) {
                return Uni.createFrom().item(RegistrationResult.failure("Service not found: " + serviceId, 404));
            }

            var existing = opt.get();

            // Check delete authorization if claims provided
            if (claims != null && !authService.isAuthorizedForService(existing, Permission.CONFIG_DELETE, claims)) {
                return Uni.createFrom()
                        .item(RegistrationResult.failure("Not authorized to delete service: " + serviceId, 403));
            }

            removeCompiledRoutes(existing);
            return cache.invalidate(serviceId)
                    .chain(() -> repository.delete(serviceId))
                    .map(deleted -> RegistrationResult.success(existing));
        });
    }

    /**
     * Get a service by ID.
     *
     * <p>
     * This method does NOT enforce authorization. Use
     * {@link #getServiceAuthorized(String, Set)} for authorized retrieval.
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
     * Get a service by ID with authorization.
     *
     * @param serviceId The service ID to find
     * @param claims    The claims from the authenticated principal
     * @return Uni with the result containing the service or an error
     */
    public Uni<RegistrationResult> getServiceAuthorized(String serviceId, Set<String> claims) {
        return getService(serviceId).map(opt -> {
            if (opt.isEmpty()) {
                return RegistrationResult.failure("Service not found: " + serviceId, 404);
            }

            var service = opt.get();

            // Check read authorization
            if (!authService.isAuthorizedForService(service, Permission.CONFIG_READ, claims)) {
                return RegistrationResult.failure("Not authorized to read service: " + serviceId, 403);
            }

            return RegistrationResult.success(service);
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
     * Update an existing service registration.
     *
     * <p>
     * This method is for updating existing services (e.g., changing permission
     * policy).
     * For full re-registration with validation, use
     * {@link #register(ServiceRegistration)}.
     *
     * @param service The updated service registration
     * @return Uni completing when the update is persisted
     */
    public Uni<Void> update(ServiceRegistration service) {
        return repository
                .save(service)
                .invoke(() -> compileAndCacheRoutes(service))
                .call(() -> cache.put(service));
    }

    /**
     * Find a route matching the given path and method across all registered
     * services.
     *
     * <p>
     * This is a synchronous operation that checks each registered service's
     * endpoints for a matching route.
     *
     * @param path   The request path
     * @param method The HTTP method
     * @return Optional containing the route lookup result if found
     */
    public Optional<RouteLookupResult> findRoute(String path, String method) {
        // Iterate through all cached services to find a matching route
        for (var route : compiledRoutes.values()) {
            var serviceRegistration = route.service();
            var routeMatch = serviceRegistration.findRoute(path, method);
            return routeMatch.isPresent()
                    ? routeMatch.map(r -> r) // Widen type from RouteMatch to RouteLookupResult
                    : Optional.of(new ServiceOnlyMatch(serviceRegistration));
        }

        return Optional.empty();
    }

    private void compileAndCacheRoutes(ServiceRegistration service) {
        for (var endpoint : service.endpoints()) {
            var routeKey = buildRouteKey(service.serviceId(), endpoint.path());
            compiledRoutes.put(routeKey, new CompiledRoute(service, endpoint));
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

    private record CompiledRoute(ServiceRegistration service, EndpointConfig endpoint) {}

    /**
     * Get a service by ID for WebSocket rate limiting.
     *
     * <p>This is a convenience method that returns the service registration
     * for use in WebSocket rate limit resolution.
     *
     * @param serviceId The service ID
     * @return Uni with Optional containing the service if found
     */
    public Uni<Optional<ServiceRegistration>> getServiceForRateLimiting(String serviceId) {
        if (serviceId == null || "unknown".equals(serviceId)) {
            return Uni.createFrom().item(Optional.empty());
        }
        return getService(serviceId);
    }
}
