package aussie.adapter.out.telemetry;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.cache.CaffeineLocalCache;
import aussie.core.cache.LocalCache;
import aussie.core.cache.LocalCacheConfig;
import aussie.core.config.SamplingConfig;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.model.sampling.EffectiveSamplingRate;
import aussie.core.model.sampling.EffectiveSamplingRate.SamplingSource;
import aussie.core.model.sampling.EndpointSamplingConfig;
import aussie.core.model.sampling.ServiceSamplingConfig;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.SamplingConfigRepository;

/**
 * Resolves effective sampling rates based on the configuration hierarchy.
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
 * All resolved rates are clamped to the platform minimum and maximum bounds.
 *
 * <p>
 * <b>Non-blocking resolution:</b> The {@link #resolveByServiceIdNonBlocking} method
 * returns the platform default immediately on cache miss and populates the cache
 * asynchronously. This prevents blocking in the OTel Sampler path.
 *
 * <p>
 * <b>Multi-instance safety:</b> The service config cache uses TTL-based
 * expiration to ensure eventual consistency across instances. When an entry
 * expires, the next lookup will fetch fresh configuration via the
 * SamplingConfigRepository.
 */
@ApplicationScoped
public class SamplingResolver {

    private static final Logger LOG = Logger.getLogger(SamplingResolver.class);

    private final SamplingConfig config;
    private final SamplingConfigRepository repository;
    private final LocalCache<String, Optional<ServiceSamplingConfig>> localCache;

    // Metrics counters
    private final Counter cachePopulateFailures;
    private final Counter platformDefaultFallbacks;

    // Cached platform default to avoid repeated allocation
    private volatile EffectiveSamplingRate cachedPlatformDefault;

    @Inject
    public SamplingResolver(
            SamplingConfig config,
            SamplingConfigRepository repository,
            LocalCacheConfig cacheConfig,
            MeterRegistry meterRegistry) {
        this.config = config;
        this.repository = repository;
        this.localCache = new CaffeineLocalCache<>(
                cacheConfig.samplingConfigTtl(), cacheConfig.maxEntries(), cacheConfig.jitterFactor());

        // Initialize metrics
        this.cachePopulateFailures = Counter.builder("aussie.sampling.cache.populate.failures")
                .description("Number of failures when populating the sampling config cache")
                .register(meterRegistry);

        this.platformDefaultFallbacks = Counter.builder("aussie.sampling.platform.fallbacks")
                .description("Number of times platform default was used due to cache miss or lookup failure")
                .register(meterRegistry);
    }

    /**
     * Resolve the effective sampling rate for an HTTP request.
     *
     * @param route the route lookup result (service + optional endpoint)
     * @return the effective sampling rate, clamped to platform bounds
     */
    public EffectiveSamplingRate resolveRate(RouteLookupResult route) {
        final var endpointConfig = route.endpoint().flatMap(EndpointConfig::samplingConfig);
        final var serviceConfig = route.service().samplingConfig();

        return resolveRate(endpointConfig, serviceConfig);
    }

    /**
     * Resolve the effective sampling rate for a service without a specific
     * endpoint.
     *
     * @param service the service registration
     * @return the effective sampling rate, clamped to platform bounds
     */
    public EffectiveSamplingRate resolveServiceRate(ServiceRegistration service) {
        return resolveRate(Optional.empty(), service.samplingConfig());
    }

    /**
     * Resolve sampling rate using service ID (non-blocking).
     *
     * <p>
     * On cache hit, returns the cached rate immediately. On cache miss,
     * returns the platform default immediately and triggers an asynchronous
     * cache population. This ensures the OTel Sampler never blocks on
     * storage lookups.
     *
     * <p>
     * <b>Trade-off:</b> First request to a new service uses the platform
     * default rate. Subsequent requests (within TTL) use the correct
     * service-specific rate.
     *
     * @param serviceId the service ID
     * @return the effective sampling rate, clamped to platform bounds
     */
    public EffectiveSamplingRate resolveByServiceIdNonBlocking(String serviceId) {
        if (serviceId == null || "unknown".equals(serviceId)) {
            return getCachedPlatformDefault();
        }

        // Check local in-memory cache first
        final var cachedConfig = localCache.get(serviceId);
        if (cachedConfig.isPresent()) {
            return resolveRate(Optional.empty(), cachedConfig.get());
        }

        // Cache miss - return platform default immediately, populate async
        populateCacheAsync(serviceId);
        platformDefaultFallbacks.increment();
        return getCachedPlatformDefault();
    }

    /**
     * Resolve sampling rate using service ID (blocking).
     *
     * <p>
     * Uses a TTL-based cache for performance. Cache entries automatically
     * expire after the configured TTL, ensuring eventual consistency when
     * sampling configs are updated on other instances.
     *
     * <p>
     * <b>Note:</b> This method blocks to await the storage lookup on cache miss.
     * Prefer {@link #resolveByServiceIdNonBlocking} for OTel Sampler calls.
     *
     * @param serviceId the service ID
     * @return the effective sampling rate, clamped to platform bounds
     */
    public EffectiveSamplingRate resolveByServiceId(String serviceId) {
        if (serviceId == null || "unknown".equals(serviceId)) {
            return getCachedPlatformDefault();
        }

        // Check local in-memory cache first
        final var cachedConfig = localCache.get(serviceId);
        if (cachedConfig.isPresent()) {
            return resolveRate(Optional.empty(), cachedConfig.get());
        }

        // Cache miss - look up via repository (blocking)
        try {
            final var serviceConfigOpt = repository
                    .findByServiceId(serviceId)
                    .await()
                    .atMost(config.lookup().timeout());

            // Cache the result (including empty for unknown services)
            localCache.put(serviceId, serviceConfigOpt);

            return resolveRate(Optional.empty(), serviceConfigOpt);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to resolve sampling config for service '%s', using platform default", serviceId);
            cachePopulateFailures.increment();
            platformDefaultFallbacks.increment();
            return getCachedPlatformDefault();
        }
    }

    /**
     * Resolve sampling rate asynchronously using service ID.
     *
     * <p>
     * Use this method when async resolution is acceptable (e.g., in filters).
     *
     * @param serviceId the service ID
     * @return Uni with the effective sampling rate
     */
    public Uni<EffectiveSamplingRate> resolveByServiceIdAsync(String serviceId) {
        if (serviceId == null || "unknown".equals(serviceId)) {
            return Uni.createFrom().item(getCachedPlatformDefault());
        }

        // Check local in-memory cache first
        final var cachedConfig = localCache.get(serviceId);
        if (cachedConfig.isPresent()) {
            return Uni.createFrom().item(resolveRate(Optional.empty(), cachedConfig.get()));
        }

        // Cache miss - look up asynchronously
        return repository
                .findByServiceId(serviceId)
                .map(serviceConfigOpt -> {
                    // Cache the result
                    localCache.put(serviceId, serviceConfigOpt);
                    return resolveRate(Optional.empty(), serviceConfigOpt);
                })
                .onFailure()
                .recoverWithItem(e -> {
                    LOG.warnf(
                            e, "Failed to resolve sampling config for service '%s', using platform default", serviceId);
                    cachePopulateFailures.increment();
                    platformDefaultFallbacks.increment();
                    return getCachedPlatformDefault();
                });
    }

    /**
     * Get the cached platform default rate.
     *
     * <p>
     * This method caches the platform default to avoid repeated allocation.
     * The cached value is invalidated when configuration changes would require
     * a restart anyway.
     *
     * @return the platform default sampling rate
     */
    public EffectiveSamplingRate getCachedPlatformDefault() {
        var cached = cachedPlatformDefault;
        if (cached == null) {
            cached = new EffectiveSamplingRate(config.defaultRate(), SamplingSource.PLATFORM)
                    .clampToPlatformBounds(config.minimumRate(), config.maximumRate());
            cachedPlatformDefault = cached;
        }
        return cached;
    }

    /**
     * Check if hierarchical sampling is enabled.
     *
     * @return true if enabled
     */
    public boolean isEnabled() {
        return config.enabled();
    }

    /**
     * Get the platform default sampling rate.
     *
     * @return the default rate (0.0 to 1.0)
     */
    public double getDefaultRate() {
        return config.defaultRate();
    }

    /**
     * Get the platform minimum sampling rate.
     *
     * @return the minimum rate
     */
    public double getMinimumRate() {
        return config.minimumRate();
    }

    /**
     * Get the platform maximum sampling rate.
     *
     * @return the maximum rate
     */
    public double getMaximumRate() {
        return config.maximumRate();
    }

    /**
     * Invalidate the cached sampling config for a service.
     *
     * <p>
     * Call this when a service registration is updated or deleted on this
     * instance. This provides immediate consistency for local changes while
     * the TTL-based expiration handles cross-instance consistency.
     *
     * @param serviceId the service ID to invalidate
     */
    public void invalidateCache(String serviceId) {
        localCache.invalidate(serviceId);
    }

    /**
     * Populate the cache asynchronously (fire-and-forget).
     */
    private void populateCacheAsync(String serviceId) {
        repository
                .findByServiceId(serviceId)
                .subscribe()
                .with(serviceConfigOpt -> localCache.put(serviceId, serviceConfigOpt), error -> {
                    LOG.warnf(error, "Async cache populate failed for service '%s'", serviceId);
                    cachePopulateFailures.increment();
                });
    }

    /**
     * Resolve the effective sampling rate from endpoint and service configs.
     *
     * <p>
     * Resolution priority:
     * <ol>
     * <li>Endpoint-level configuration (if present and has value)</li>
     * <li>Service-level configuration (if present and has value)</li>
     * <li>Platform defaults</li>
     * </ol>
     */
    private EffectiveSamplingRate resolveRate(
            Optional<EndpointSamplingConfig> endpointConfig, Optional<ServiceSamplingConfig> serviceConfig) {

        // Endpoint-level has highest priority
        if (endpointConfig.isPresent() && endpointConfig.get().samplingRate().isPresent()) {
            return new EffectiveSamplingRate(endpointConfig.get().samplingRate().get(), SamplingSource.ENDPOINT)
                    .clampToPlatformBounds(config.minimumRate(), config.maximumRate());
        }

        // Service-level has second priority
        if (serviceConfig.isPresent() && serviceConfig.get().samplingRate().isPresent()) {
            return new EffectiveSamplingRate(serviceConfig.get().samplingRate().get(), SamplingSource.SERVICE)
                    .clampToPlatformBounds(config.minimumRate(), config.maximumRate());
        }

        // Fall back to platform default
        return getCachedPlatformDefault();
    }
}
