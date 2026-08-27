package aussie.core.service.routing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import org.jboss.logging.Logger;

import aussie.core.cache.LocalCacheConfig;
import aussie.core.model.auth.Permission;
import aussie.core.model.auth.ServicePermissionPolicy;
import aussie.core.model.common.CorsConfig;
import aussie.core.model.common.ValidationResult;
import aussie.core.model.routing.GatewaySnapshot;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.model.routing.RoutingSnapshotStatus;
import aussie.core.model.routing.ServiceOnlyMatch;
import aussie.core.model.service.ConditionalWriteResult;
import aussie.core.model.service.RegistrationResult;
import aussie.core.model.service.ServiceConfigEvent;
import aussie.core.model.service.ServicePath;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.ConfigurationCache;
import aussie.core.port.out.ServiceConfigEventPublisher;
import aussie.core.port.out.ServiceRegistrationRepository;
import aussie.core.service.auth.ServiceAuthorizationService;

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
 *
 * <p>
 * <b>Multi-instance safety:</b> The compiled route cache uses TTL-based refresh
 * to ensure eventual consistency across instances. Additionally, service
 * configuration events are published via pub/sub for immediate cross-instance
 * cache invalidation.
 */
@ApplicationScoped
public class ServiceRegistry {

    private static final Logger LOG = Logger.getLogger(ServiceRegistry.class);

    private final ServiceRegistrationRepository repository;
    private final ConfigurationCache cache;
    private final ServiceRegistrationValidator validator;
    private final ServiceAuthorizationService authService;
    private final ServiceConfigEventPublisher eventPublisher;
    private final Duration routeCacheTtl;

    // Every request observes one fully compiled, immutable generation.
    private final AtomicReference<RoutingState> routingState =
            new AtomicReference<>(new RoutingState(GatewaySnapshot.empty(), 0L, checksum(List.of())));
    private final AtomicLong durableGeneration = new AtomicLong();
    private final AtomicReference<RoutingSnapshotStatus.RejectedGeneration> lastRejectedGeneration =
            new AtomicReference<>();

    // TTL tracking for multi-instance cache refresh
    private final AtomicReference<Instant> lastRefreshed = new AtomicReference<>(Instant.MIN);

    // Coalesces concurrent refresh requests to prevent thundering herd
    private final AtomicReference<Uni<Void>> inFlightRefresh = new AtomicReference<>();

    // Subscription handle for config event stream (for cleanup on shutdown)
    private volatile Cancellable configEventSubscription;

    @Inject
    public ServiceRegistry(
            ServiceRegistrationRepository repository,
            ConfigurationCache cache,
            ServiceRegistrationValidator validator,
            ServiceAuthorizationService authService,
            ServiceConfigEventPublisher eventPublisher,
            LocalCacheConfig cacheConfig) {
        this.repository = repository;
        this.cache = cache;
        this.validator = validator;
        this.authService = authService;
        this.eventPublisher = eventPublisher;
        this.routeCacheTtl = cacheConfig.serviceRoutesTtl();
    }

    /**
     * Initialize route cache from persistent storage on startup and
     * subscribe to service configuration events for cross-instance
     * cache invalidation.
     *
     * @return Uni completing when initialization is done
     */
    public Uni<Void> initialize() {
        subscribeToConfigEvents();
        return refreshRouteCache();
    }

    /**
     * Subscribe to service config events for cross-instance cache invalidation.
     *
     * <p>On receiving a newer event, the complete durable configuration is rebuilt and
     * atomically published. This prevents request threads from observing a mixture of generations.
     *
     * <p>Note: the originating instance also receives its own events (self-echo).
     * The redundant re-fetch is harmless since the data is already current.
     *
     * <p>If the subscription terminates due to an error (e.g., lost Redis
     * connection), retry with exponential backoff is attempted. The TTL-based
     * refresh in {@link #ensureCacheFresh()} provides a fallback for eventual
     * consistency during reconnection.
     */
    private void subscribeToConfigEvents() {
        configEventSubscription = eventPublisher
                .subscribe()
                .onFailure()
                .invoke(err -> LOG.warnf(err, "Error in service config event subscription, retrying"))
                .onFailure()
                .retry()
                .withBackOff(Duration.ofSeconds(1), Duration.ofSeconds(30))
                .indefinitely()
                .subscribe()
                .with(
                        this::handleConfigEvent,
                        error -> LOG.errorf(error, "Service config event subscription terminated unexpectedly"));
    }

    @PreDestroy
    void shutdown() {
        if (configEventSubscription != null) {
            configEventSubscription.cancel();
        }
    }

    /** Dispatch a received config event to the appropriate handler. */
    private void handleConfigEvent(ServiceConfigEvent event) {
        if (event.generation() > 0L && event.generation() <= routingState.get().generation()) {
            return;
        }
        LOG.debugf("Received service config event: serviceId=%s generation=%d", event.serviceId(), event.generation());
        refreshRouteCache()
                .subscribe()
                .with(
                        v -> LOG.debugf(
                                "Published routing generation %d",
                                routingState.get().generation()),
                        err -> LOG.warnf(err, "Rejected service config generation %d", event.generation()));
    }

    /**
     * Refresh all routes from persistent storage.
     *
     * <p>
     * Builds and atomically publishes all valid service registrations from storage,
     * then updates the last-refreshed timestamp for TTL tracking.
     *
     * @return Uni completing when refresh is done
     */
    private Uni<Void> refreshRouteCache() {
        return refreshRouteCache(0);
    }

    private Uni<Void> refreshRouteCache(int attempt) {
        return repository
                .currentGeneration()
                .flatMap(before -> {
                    durableGeneration.accumulateAndGet(before, Math::max);
                    return repository.findAll().flatMap(registrations -> repository
                            .currentGeneration()
                            .flatMap(after -> {
                                durableGeneration.accumulateAndGet(after, Math::max);
                                if (before != after && attempt < 4) {
                                    return refreshRouteCache(attempt + 1);
                                }
                                if (before != after) {
                                    return Uni.createFrom()
                                            .failure(new IllegalStateException("Configuration changed during refresh"));
                                }
                                final var validRegistrations = registrations.stream()
                                        .filter(this::isValidForRouting)
                                        .toList();
                                final var candidate = GatewaySnapshot.build(validRegistrations);
                                final var candidateState =
                                        new RoutingState(candidate, after, checksum(validRegistrations));
                                routingState.accumulateAndGet(
                                        candidateState,
                                        (current, next) -> next.generation() < current.generation() ? current : next);
                                lastRefreshed.set(Instant.now());
                                return Uni.createFrom().voidItem();
                            }));
                })
                .onFailure()
                .invoke(error -> lastRejectedGeneration.set(new RoutingSnapshotStatus.RejectedGeneration(
                        durableGeneration.get(), error.getMessage(), Instant.now())));
    }

    /**
     * Check if the route cache is stale and needs refresh.
     *
     * @return true if cache TTL has expired
     */
    private boolean isCacheStale() {
        final var lastRefresh = lastRefreshed.get();
        return lastRefresh.plus(routeCacheTtl).isBefore(Instant.now());
    }

    /**
     * Ensures the route cache is fresh, refreshing from storage if needed.
     *
     * <p>
     * This method provides eventual consistency for multi-instance deployments.
     * When the cache TTL expires, routes are reloaded from persistent storage.
     *
     * <p>
     * Uses request coalescing to prevent thundering herd: concurrent callers
     * share a single in-flight refresh rather than triggering multiple refreshes.
     *
     * @return Uni completing when cache is ensured fresh
     */
    private Uni<Void> ensureCacheFresh() {
        if (!isCacheStale()) {
            return Uni.createFrom().voidItem();
        }

        return Uni.createFrom().deferred(() -> {
            // Re-check after entering deferred block; another thread may have refreshed
            if (!isCacheStale()) {
                return Uni.createFrom().voidItem();
            }

            // Join an in-flight refresh if one exists
            var existing = inFlightRefresh.get();
            if (existing != null) {
                return existing;
            }

            // Create new refresh that clears itself on completion
            var refresh = refreshRouteCache()
                    .onTermination()
                    .invoke(() -> inFlightRefresh.set(null))
                    .memoize()
                    .indefinitely();

            // Try to set as the in-flight refresh; if another thread won, use theirs
            if (inFlightRefresh.compareAndSet(null, refresh)) {
                return refresh;
            }
            var winner = inFlightRefresh.get();
            return winner != null ? winner : refresh;
        });
    }

    /**
     * Register a new service or update an existing one.
     *
     * <p>
     * Validate the registration against gateway policies before persisting.
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
     * Validate the registration against gateway policies before persisting.
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
        return register(service, claims, null);
    }

    /** Register or update a service with an optional current-version precondition. */
    public Uni<RegistrationResult> register(ServiceRegistration service, Set<String> claims, Long expectedVersion) {
        // Validate against gateway policies
        ValidationResult validationResult = validator.validate(service);
        if (validationResult instanceof ValidationResult.Invalid invalid) {
            return Uni.createFrom().item(RegistrationResult.failure(invalid.reason(), invalid.suggestedStatusCode()));
        }

        // Check version constraint and authorization
        return repository.findById(service.serviceId()).flatMap(existingOpt -> {
            if (existingOpt.isEmpty()) {
                // Check create authorization if claims provided
                if (claims != null && !authService.canCreateService(service.serviceId(), claims)) {
                    return Uni.createFrom()
                            .item(RegistrationResult.failure(
                                    "Not authorized to create service: " + service.serviceId(), 403));
                }

                if (expectedVersion != null) {
                    return Uni.createFrom()
                            .item(RegistrationResult.failure(
                                    "If-Match requires an existing service registration", 412));
                }

                // New service: version must be 1
                if (service.version() != 1L) {
                    return Uni.createFrom()
                            .item(RegistrationResult.failure(
                                    "New service registration must have version 1, got " + service.version(), 409));
                }

            } else {
                var existing = existingOpt.get();

                // Check update authorization before exposing version state
                if (claims != null
                        && !authService.isAuthorizedForService(existing, Permission.CONFIG_UPDATE.value(), claims)) {
                    return Uni.createFrom()
                            .item(RegistrationResult.failure(
                                    "Not authorized to update service: " + service.serviceId(), 403));
                }

                if (expectedVersion != null && existing.version() != expectedVersion) {
                    return Uni.createFrom().item(preconditionFailed());
                }

                // Update: version must be current + 1
                long currentVersion = existing.version();
                long nextVersion = currentVersion + 1;
                if (service.version() != nextVersion) {
                    return Uni.createFrom()
                            .item(RegistrationResult.failure(
                                    "Version conflict: expected version " + nextVersion + " (current is "
                                            + currentVersion + "), got " + service.version(),
                                    409));
                }

                // Check if permission policy is changing (not just present)
                if (claims != null && hasPermissionPolicyChanged(existing, service)) {
                    if (!authService.isAuthorizedForService(existing, Permission.PERMISSIONS_WRITE.value(), claims)) {
                        return Uni.createFrom()
                                .item(RegistrationResult.failure(
                                        "Not authorized to update permissions for service: " + service.serviceId(),
                                        403));
                    }
                }
            }

            try {
                routingState.get().snapshot().with(service);
            } catch (IllegalArgumentException conflict) {
                return Uni.createFrom().item(RegistrationResult.failure(conflict.getMessage(), 409));
            }

            final Uni<ConditionalWriteResult> write = existingOpt.isEmpty()
                    ? repository.createIfAbsent(service)
                    : repository.replaceIfVersion(service, existingOpt.get().version());
            return write.flatMap(result -> {
                if (!result.applied()) {
                    return Uni.createFrom()
                            .item(expectedVersion == null ? versionConflict(result) : preconditionFailed());
                }
                return publishChange(service).replaceWith(RegistrationResult.success(service));
            });
        });
    }

    private RegistrationResult versionConflict(ConditionalWriteResult result) {
        final var reason = result.currentVersion().isPresent()
                ? "Version conflict: service changed concurrently; current version is "
                        + result.currentVersion().getAsLong()
                : "Version conflict: service no longer exists";
        return RegistrationResult.failure(reason, 409);
    }

    /**
     * Check if the permission policy has changed between existing and new service.
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
            if (opt.isEmpty()) {
                return Uni.createFrom().item(false);
            }
            return repository.deleteIfVersion(serviceId, opt.get().version()).flatMap(result -> {
                if (!result.applied()) {
                    return Uni.createFrom().item(false);
                }
                return publishRemoval(serviceId).replaceWith(true);
            });
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
        return unregisterAuthorized(serviceId, claims, null);
    }

    /** Delete a service only when its current version matches the supplied ETag. */
    public Uni<RegistrationResult> unregisterAuthorized(String serviceId, Set<String> claims, Long expectedVersion) {
        return repository.findById(serviceId).flatMap(opt -> {
            if (opt.isEmpty()) {
                return Uni.createFrom().item(RegistrationResult.failure("Service not found: " + serviceId, 404));
            }

            var existing = opt.get();

            // Check delete authorization before exposing version state
            if (claims != null
                    && !authService.isAuthorizedForService(existing, Permission.CONFIG_DELETE.value(), claims)) {
                return Uni.createFrom()
                        .item(RegistrationResult.failure("Not authorized to delete service: " + serviceId, 403));
            }

            if (expectedVersion != null && existing.version() != expectedVersion) {
                return Uni.createFrom().item(preconditionFailed());
            }

            return repository.deleteIfVersion(serviceId, existing.version()).flatMap(result -> {
                if (!result.applied()) {
                    return Uni.createFrom()
                            .item(expectedVersion == null ? versionConflict(result) : preconditionFailed());
                }
                return publishRemoval(serviceId).replaceWith(RegistrationResult.success(existing));
            });
        });
    }

    /**
     * Get a routable service by ID from the validated local snapshot.
     *
     * <p>The snapshot is refreshed from persistent storage when stale. Registrations that
     * fail current gateway policy are deliberately absent, so every request-path consumer
     * shares the same containment boundary. This method does NOT enforce authorization. Use
     * {@link #getServiceAuthorized(String, Set)} for authorized retrieval.
     *
     * @param serviceId The service ID to find
     * @return Uni with Optional containing the service if found
     */
    public Uni<Optional<ServiceRegistration>> getService(String serviceId) {
        return ensureCacheFresh().map(ignored -> getServiceFromLocalCache(serviceId));
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
            if (!authService.isAuthorizedForService(service, Permission.CONFIG_READ.value(), claims)) {
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

    public Uni<List<ServiceRegistration>> getServices(int limit, int offset) {
        return repository.findPage(limit, offset);
    }

    /**
     * Return only services the caller can read, then apply the page bounds.
     *
     * <p>The repository contract has no ownership predicate, so authorization
     * must happen before pagination or a caller could infer hidden rows from
     * page boundaries. The result remains bounded; a storage-level scoped query
     * is the follow-up when tenant ownership is persisted as a column.
     */
    public Uni<List<ServiceRegistration>> getServicesAuthorized(int limit, int offset, Set<String> claims) {
        // ponytail: full scan preserves authorization before pagination; add a scoped repository query at scale.
        return repository.findAll().map(services -> services.stream()
                .filter(service -> authService.isAuthorizedForService(service, Permission.CONFIG_READ.value(), claims))
                .sorted(Comparator.comparing(ServiceRegistration::serviceId))
                .skip(offset)
                .limit(limit)
                .toList());
    }

    private RegistrationResult preconditionFailed() {
        return RegistrationResult.failure("If-Match does not match the current service version", 412);
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
     * @return Uni with the update result
     */
    public Uni<RegistrationResult> update(ServiceRegistration service) {
        final var validationResult = validator.validate(service);
        if (validationResult instanceof ValidationResult.Invalid invalid) {
            return Uni.createFrom().item(RegistrationResult.failure(invalid.reason(), invalid.suggestedStatusCode()));
        }
        routingState.get().snapshot().with(service);
        return repository.replaceIfVersion(service, service.version() - 1).flatMap(result -> {
            if (!result.applied()) {
                return Uni.createFrom().item(versionConflict(result));
            }
            return publishChange(service).replaceWith(RegistrationResult.success(service));
        });
    }

    private Uni<Void> publishChange(ServiceRegistration service) {
        return refreshRouteCache()
                .call(() -> eventPublisher.publishServiceChanged(
                        service.serviceId(), routingState.get().generation()))
                .call(() -> cache.put(service)
                        .onFailure()
                        .invoke(err -> LOG.warnf(err, "Failed to update cache for service: %s", service.serviceId()))
                        .onFailure()
                        .recoverWithNull());
    }

    private Uni<Void> publishRemoval(String serviceId) {
        return refreshRouteCache()
                .call(() -> eventPublisher.publishServiceRemoved(
                        serviceId, routingState.get().generation()))
                .call(() -> cache.invalidate(serviceId)
                        .onFailure()
                        .invoke(err -> LOG.warnf(err, "Failed to invalidate cache for service: %s", serviceId))
                        .onFailure()
                        .recoverWithNull());
    }

    /** Return local routing state together with the latest durable generation. */
    public Uni<RoutingSnapshotStatus> routingStatus() {
        return repository.currentGeneration().map(generation -> {
            durableGeneration.accumulateAndGet(generation, Math::max);
            return localRoutingStatus();
        });
    }

    RoutingSnapshotStatus localRoutingStatus() {
        final var state = routingState.get();
        final var active = state.generation();
        final var durable = durableGeneration.get();
        return new RoutingSnapshotStatus(
                active,
                durable,
                Math.max(0L, durable - active),
                state.checksum(),
                Optional.ofNullable(lastRejectedGeneration.get()));
    }

    private static String checksum(List<ServiceRegistration> registrations) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            registrations.stream()
                    .sorted(Comparator.comparing(ServiceRegistration::serviceId))
                    .map(service -> service.serviceId() + "\0" + service.version() + "\n")
                    .forEach(value -> digest.update(value.getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Find a route matching the given path and method across all registered
     * services (async version with cache freshness).
     *
     * <p>
     * This is the preferred method for multi-instance deployments as it ensures
     * the route cache is fresh before searching. If the cache TTL has expired,
     * routes are reloaded from persistent storage first.
     *
     * @param path   The request path
     * @param method The HTTP method
     * @return Uni with Optional containing the route lookup result if found
     */
    public Uni<Optional<RouteLookupResult>> findRouteAsync(String path, String method) {
        return ensureCacheFresh().map(v -> findRouteInCache(path, method));
    }

    /**
     * Find a route matching the given path and method across all registered
     * services.
     *
     * <p>
     * This is a synchronous operation over the current compiled snapshot. Note:
     * This method does NOT refresh the cache from storage. For multi-instance safe lookups, use
     * {@link #findRouteAsync(String, String)}.
     *
     * @param path   The request path
     * @param method The HTTP method
     * @return Optional containing the route lookup result if found
     */
    public Optional<RouteLookupResult> findRoute(String path, String method) {
        return findRouteInCache(path, method);
    }

    /**
     * Internal method to find a route in the local cache.
     *
     * <p>
     * Handles two routing modes:
     * <ul>
     * <li><b>Gateway mode</b> ({@code /gateway/...} or endpoint-only paths): Strips the /gateway
     *     prefix if present and matches the endpoint path against all services' endpoints.</li>
     * <li><b>Pass-through mode</b> ({@code /{serviceId}/...}): Extracts the service ID
     *     from the path and matches the endpoint path against that service's endpoints.</li>
     * </ul>
     *
     * <p>
     * The method automatically detects which mode to use based on whether the first path
     * segment matches a registered service ID.
     */
    private Optional<RouteLookupResult> findRouteInCache(String path, String method) {
        final var currentSnapshot = routingState.get().snapshot();

        // Explicit gateway mode: /gateway/api/users -> match /api/users against all services
        if (path != null && (path.toLowerCase().startsWith("/gateway/") || path.equalsIgnoreCase("/gateway"))) {
            final var endpointPath = path.length() > "/gateway".length() ? path.substring("/gateway".length()) : "/";
            return findRouteByEndpointPath(currentSnapshot, endpointPath, method);
        }

        // Parse the path to extract potential service ID and endpoint path
        final var servicePath = ServicePath.parse(path);
        final var serviceId = servicePath.serviceId();
        final var endpointPath = servicePath.path();

        // Check if the parsed serviceId corresponds to a registered service
        final var routes = currentSnapshot.service(serviceId);

        if (routes.isPresent()) {
            // Pass-through mode: /demo-service/api/users -> match /api/users against demo-service
            final var service = routes.get();
            final var match = currentSnapshot.match(serviceId, normalizePath(endpointPath), method.toUpperCase());
            if (match != null) {
                return Optional.of(match);
            }
            return Optional.of(new ServiceOnlyMatch(service));
        }

        // Implicit gateway mode: No matching serviceId found, so treat the path as an
        // endpoint path and search all services. This handles calls from GatewayService
        // where the path is just the endpoint (e.g., "/api/users" without "/gateway" prefix).
        return findRouteByEndpointPath(currentSnapshot, path, method);
    }

    /**
     * Find a route by matching the endpoint path against all services.
     * Used for gateway mode where the path doesn't include a service prefix.
     *
     * <p>
     * If an exact endpoint match is found, returns a {@link RouteMatch}.
     */
    private Optional<RouteLookupResult> findRouteByEndpointPath(
            GatewaySnapshot currentSnapshot, String endpointPath, String method) {
        final var normalizedPath = normalizePath(endpointPath);
        final var upperMethod = method.toUpperCase();
        return Optional.ofNullable(currentSnapshot.match(normalizedPath, upperMethod));
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private boolean isValidForRouting(ServiceRegistration service) {
        final var validationResult = validator.validate(service);
        if (validationResult instanceof ValidationResult.Invalid invalid) {
            LOG.errorf(
                    "Service excluded from routing by gateway policy: serviceId=%s reason=%s",
                    service.serviceId(), invalid.reason());
            return false;
        }
        return true;
    }

    /**
     * Get a service by ID from the local in-memory cache (non-blocking).
     *
     * <p>This method is intended for use in contexts where blocking is not
     * allowed, such as Vert.x RouteFilters. It returns data from the local
     * compiled route cache without any I/O. The cache is kept fresh by TTL
     * refresh and pub/sub event invalidation.
     *
     * @param serviceId The service ID to look up
     * @return Optional containing the service if found in local cache
     */
    public Optional<ServiceRegistration> getServiceFromLocalCache(String serviceId) {
        if (serviceId == null || ServicePath.UNKNOWN_SERVICE.equals(serviceId)) {
            return Optional.empty();
        }
        return routingState.get().snapshot().service(serviceId);
    }

    /** Resolve a registered service CORS policy from the compiled local snapshot. */
    public Optional<CorsConfig> getCorsConfigForOriginFromLocalCache(String origin) {
        return routingState.get().snapshot().corsConfigForOrigin(origin);
    }

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

    private record RoutingState(GatewaySnapshot snapshot, long generation, String checksum) {}
}
