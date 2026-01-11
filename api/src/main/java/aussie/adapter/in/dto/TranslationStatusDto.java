package aussie.adapter.in.dto;

/**
 * DTO for token translation service status and introspection.
 *
 * @param enabled           whether token translation is enabled
 * @param activeProvider    name of the currently active provider
 * @param providerHealthy   whether the active provider is healthy
 * @param cache             cache statistics
 */
public record TranslationStatusDto(boolean enabled, String activeProvider, boolean providerHealthy, CacheStatus cache) {

    /**
     * Cache statistics for the translation service.
     *
     * @param currentSize current number of cached entries
     * @param maxSize     maximum cache size
     * @param ttlSeconds  cache TTL in seconds
     */
    public record CacheStatus(long currentSize, long maxSize, int ttlSeconds) {}

    /**
     * Create a status DTO from service state.
     *
     * @param enabled         translation enabled flag
     * @param activeProvider  active provider name
     * @param providerHealthy provider health status
     * @param cacheSize       current cache size
     * @param cacheMaxSize    max cache size
     * @param cacheTtlSeconds cache TTL
     * @return status DTO
     */
    public static TranslationStatusDto create(
            boolean enabled,
            String activeProvider,
            boolean providerHealthy,
            long cacheSize,
            long cacheMaxSize,
            int cacheTtlSeconds) {

        return new TranslationStatusDto(
                enabled, activeProvider, providerHealthy, new CacheStatus(cacheSize, cacheMaxSize, cacheTtlSeconds));
    }
}
