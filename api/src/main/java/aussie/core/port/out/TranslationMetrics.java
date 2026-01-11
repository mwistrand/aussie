package aussie.core.port.out;

import aussie.core.model.auth.TranslationOutcome;

/**
 * Port interface for recording token translation metrics.
 *
 * <p>Defines the contract for metrics recording, allowing the core service
 * layer to remain decoupled from specific metrics implementations.
 */
public interface TranslationMetrics {

    /**
     * Record a translation operation.
     *
     * @param provider   the provider name that performed the translation
     * @param outcome    the outcome (success, error, fallback)
     * @param durationMs duration in milliseconds
     */
    void recordTranslation(String provider, TranslationOutcome outcome, long durationMs);

    /**
     * Record a cache hit.
     */
    void recordCacheHit();

    /**
     * Record a cache miss.
     */
    void recordCacheMiss();

    /**
     * Update the cache size gauge.
     *
     * @param size current cache size
     */
    void updateCacheSize(long size);

    /**
     * Record a translation error.
     *
     * @param provider  the provider that encountered the error
     * @param errorType the type of error
     */
    void recordError(String provider, String errorType);
}
