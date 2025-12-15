package aussie.core.service.ratelimit;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.cache.CaffeineLocalCache;
import aussie.core.cache.LocalCache;
import aussie.core.cache.LocalCacheConfig;
import aussie.core.config.RateLimitingConfig;
import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.EndpointRateLimitConfig;
import aussie.core.model.ratelimit.ServiceRateLimitConfig;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.routing.ServiceRegistry;

/**
 * Resolves effective rate limits based on the configuration hierarchy.
 *
 * <p>
 * Resolution priority (highest to lowest):
 * <ol>
 * <li>Endpoint-specific configuration</li>
 * <li>Service-level configuration</li>
 * <li>Platform defaults</li>
 * </ol>
 *
 * <p>
 * All resolved limits are capped at the platform maximum to ensure
 * no service or endpoint can exceed platform-wide limits.
 *
 * <p>
 * <b>Multi-instance safety:</b> The service config cache uses TTL-based expiration
 * to ensure eventual consistency across instances. When an entry expires, the
 * next lookup will fetch fresh configuration via the ServiceRegistry.
 */
@ApplicationScoped
public class RateLimitResolver {

    private final RateLimitingConfig config;
    private final ServiceRegistry serviceRegistry;

    // TTL-based cache for service rate limit configs
    private final LocalCache<String, Optional<ServiceRateLimitConfig>> serviceConfigCache;

    @Inject
    public RateLimitResolver(RateLimitingConfig config, ServiceRegistry serviceRegistry, LocalCacheConfig cacheConfig) {
        this.config = config;
        this.serviceRegistry = serviceRegistry;
        this.serviceConfigCache = new CaffeineLocalCache<>(
                cacheConfig.rateLimitConfigTtl(), cacheConfig.maxEntries(), cacheConfig.jitterFactor());
    }

    /**
     * Resolves the effective rate limit for an HTTP request.
     *
     * @param route the route lookup result (service + optional endpoint)
     * @return the effective rate limit, capped at platform maximum
     */
    public EffectiveRateLimit resolveLimit(RouteLookupResult route) {
        final var endpointConfig = route.endpoint().flatMap(e -> e.rateLimitConfig());
        final var serviceConfig = route.service().rateLimitConfig();

        return resolveLimit(
                endpointConfig,
                serviceConfig,
                config.defaultRequestsPerWindow(),
                config.windowSeconds(),
                config.burstCapacity());
    }

    /**
     * Resolves the effective rate limit for a service without a specific endpoint.
     *
     * @param service the service registration
     * @return the effective rate limit, capped at platform maximum
     */
    public EffectiveRateLimit resolveServiceLimit(ServiceRegistration service) {
        return resolveLimit(
                Optional.empty(),
                service.rateLimitConfig(),
                config.defaultRequestsPerWindow(),
                config.windowSeconds(),
                config.burstCapacity());
    }

    /**
     * Resolves the effective rate limit using only service ID (for pass-through
     * mode).
     *
     * <p>
     * Looks up the service registration to honor service-specific rate limit
     * configuration. Falls back to platform defaults if service not found.
     *
     * <p>
     * Uses a TTL-based cache for multi-instance safety. Cache entries
     * automatically expire after the configured TTL, ensuring eventual
     * consistency when rate limit configs are updated on other instances.
     *
     * @param serviceId the service ID
     * @return Uni with the effective rate limit, capped at platform maximum
     */
    public Uni<EffectiveRateLimit> resolveByServiceId(String serviceId) {
        if (serviceId == null || "unknown".equals(serviceId)) {
            return Uni.createFrom().item(resolvePlatformDefaults());
        }

        // Check TTL-based cache first
        final var cachedConfig = serviceConfigCache.get(serviceId);
        if (cachedConfig.isPresent()) {
            return Uni.createFrom()
                    .item(resolveLimit(
                            Optional.empty(),
                            cachedConfig.get(),
                            config.defaultRequestsPerWindow(),
                            config.windowSeconds(),
                            config.burstCapacity()));
        }

        // Cache miss - look up service registration via ServiceRegistry
        return serviceRegistry.getServiceForRateLimiting(serviceId).map(serviceOpt -> {
            final var serviceConfig =
                    serviceOpt.map(ServiceRegistration::rateLimitConfig).orElse(Optional.empty());

            // Cache the result (including empty for unknown services)
            serviceConfigCache.put(serviceId, serviceConfig);

            return resolveLimit(
                    Optional.empty(),
                    serviceConfig,
                    config.defaultRequestsPerWindow(),
                    config.windowSeconds(),
                    config.burstCapacity());
        });
    }

    /**
     * Invalidates the cached rate limit config for a service.
     *
     * <p>
     * Call this when a service registration is updated or deleted on this
     * instance. This provides immediate consistency for local changes while
     * the TTL-based expiration handles cross-instance consistency.
     *
     * @param serviceId the service ID to invalidate
     */
    public void invalidateCache(String serviceId) {
        serviceConfigCache.invalidate(serviceId);
    }

    /**
     * Resolves the effective WebSocket connection rate limit.
     *
     * @param service the service registration (optional)
     * @return the effective rate limit for WebSocket connections
     */
    public EffectiveRateLimit resolveWebSocketConnectionLimit(Optional<ServiceRegistration> service) {
        // Start with platform WebSocket defaults
        final var platformConfig = config.websocket().connection();
        var requestsPerWindow = platformConfig.requestsPerWindow();
        var windowSeconds = platformConfig.windowSeconds();
        var burstCapacity = platformConfig.burstCapacity();

        // Apply service-level WebSocket connection overrides if present
        final var serviceWsConfig = service.flatMap(ServiceRegistration::rateLimitConfig)
                .flatMap(ServiceRateLimitConfig::websocket)
                .flatMap(ws -> ws.connection());

        if (serviceWsConfig.isPresent()) {
            final var svcConfig = serviceWsConfig.get();
            requestsPerWindow = svcConfig.requestsPerWindow().orElse(requestsPerWindow);
            windowSeconds = svcConfig.windowSeconds().orElse(windowSeconds);
            burstCapacity = svcConfig.burstCapacity().orElse(burstCapacity);
        }

        return new EffectiveRateLimit(requestsPerWindow, windowSeconds, burstCapacity)
                .capAtPlatformMax(config.platformMaxRequestsPerWindow());
    }

    /**
     * Resolves the effective WebSocket message rate limit.
     *
     * @param service the service registration (optional)
     * @return the effective rate limit for WebSocket messages
     */
    public EffectiveRateLimit resolveWebSocketMessageLimit(Optional<ServiceRegistration> service) {
        // Start with platform WebSocket defaults
        final var platformConfig = config.websocket().message();
        var requestsPerWindow = platformConfig.requestsPerWindow();
        var windowSeconds = platformConfig.windowSeconds();
        var burstCapacity = platformConfig.burstCapacity();

        // Apply service-level WebSocket message overrides if present
        final var serviceWsConfig = service.flatMap(ServiceRegistration::rateLimitConfig)
                .flatMap(ServiceRateLimitConfig::websocket)
                .flatMap(ws -> ws.message());

        if (serviceWsConfig.isPresent()) {
            final var svcConfig = serviceWsConfig.get();
            requestsPerWindow = svcConfig.requestsPerWindow().orElse(requestsPerWindow);
            windowSeconds = svcConfig.windowSeconds().orElse(windowSeconds);
            burstCapacity = svcConfig.burstCapacity().orElse(burstCapacity);
        }

        return new EffectiveRateLimit(requestsPerWindow, windowSeconds, burstCapacity)
                .capAtPlatformMax(config.platformMaxRequestsPerWindow());
    }

    /**
     * Resolves platform default limits.
     *
     * @return the platform default rate limit
     */
    public EffectiveRateLimit resolvePlatformDefaults() {
        return new EffectiveRateLimit(config.defaultRequestsPerWindow(), config.windowSeconds(), config.burstCapacity())
                .capAtPlatformMax(config.platformMaxRequestsPerWindow());
    }

    /**
     * Checks if rate limiting is enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return config.enabled();
    }

    /**
     * Gets the platform maximum requests per window.
     *
     * @return platform maximum
     */
    public long getPlatformMax() {
        return config.platformMaxRequestsPerWindow();
    }

    private EffectiveRateLimit resolveLimit(
            Optional<EndpointRateLimitConfig> endpointConfig,
            Optional<ServiceRateLimitConfig> serviceConfig,
            long defaultRequests,
            long defaultWindow,
            long defaultBurst) {

        // Start with platform defaults
        long requestsPerWindow = defaultRequests;
        long windowSeconds = defaultWindow;
        long burstCapacity = defaultBurst;

        // Apply service-level overrides
        if (serviceConfig.isPresent()) {
            final var svc = serviceConfig.get();
            requestsPerWindow = svc.requestsPerWindow().orElse(requestsPerWindow);
            windowSeconds = svc.windowSeconds().orElse(windowSeconds);
            burstCapacity = svc.burstCapacity().orElse(burstCapacity);
        }

        // Apply endpoint-level overrides (highest priority)
        if (endpointConfig.isPresent()) {
            final var ep = endpointConfig.get();
            requestsPerWindow = ep.requestsPerWindow().orElse(requestsPerWindow);
            windowSeconds = ep.windowSeconds().orElse(windowSeconds);
            burstCapacity = ep.burstCapacity().orElse(burstCapacity);
        }

        // Create and cap at platform maximum
        return new EffectiveRateLimit(requestsPerWindow, windowSeconds, burstCapacity)
                .capAtPlatformMax(config.platformMaxRequestsPerWindow());
    }
}
