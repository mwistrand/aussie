package aussie.core.service.auth;

import java.time.Duration;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.cache.CaffeineLocalCache;
import aussie.core.cache.LocalCache;
import aussie.core.config.TokenTranslationConfig;
import aussie.core.model.auth.ClaimTranslator;
import aussie.core.model.auth.TranslatedClaims;
import aussie.core.model.auth.TranslationConfigSchema;
import aussie.core.model.auth.TranslationOutcome;
import aussie.core.port.out.TranslationMetrics;

/**
 * Service for translating external IdP token claims to Aussie's authorization model.
 *
 * <p>Delegates to the configured provider to perform claim translation. The default
 * provider extracts roles and permissions from the standard "roles" and "permissions"
 * claims directly.
 *
 * <p>Translation results are cached to avoid repeated processing of the same token.
 */
@ApplicationScoped
public class TokenTranslationService {

    private static final Logger LOG = Logger.getLogger(TokenTranslationService.class);

    private final TokenTranslationConfig config;
    private final TokenTranslatorProviderRegistry registry;
    private final TranslationMetrics metrics;
    private LocalCache<String, TranslatedClaims> cache;

    @Inject
    public TokenTranslationService(
            TokenTranslationConfig config, TokenTranslatorProviderRegistry registry, TranslationMetrics metrics) {
        this.config = config;
        this.registry = registry;
        this.metrics = metrics;
    }

    @PostConstruct
    void init() {
        final var cacheConfig = config.cache();
        this.cache = new CaffeineLocalCache<>(Duration.ofSeconds(cacheConfig.ttlSeconds()), cacheConfig.maxSize());

        LOG.infof(
                "Token translation service initialized: enabled=%s, provider=%s, cache.ttl=%ds, cache.maxSize=%d",
                config.enabled(), config.provider(), cacheConfig.ttlSeconds(), cacheConfig.maxSize());
    }

    /**
     * Translate token claims to Aussie's authorization model.
     *
     * <p>Results are cached based on the token's unique identifier (jti claim if present,
     * otherwise a composite of issuer, subject, and issued-at time).
     *
     * @param issuer  the token issuer (iss claim)
     * @param subject the token subject (sub claim)
     * @param claims  all claims from the validated token
     * @return translated roles and permissions
     */
    public Uni<TranslatedClaims> translate(String issuer, String subject, Map<String, Object> claims) {
        final var cacheKey = buildCacheKey(issuer, subject, claims);
        final var startTime = System.currentTimeMillis();
        final var provider = registry.getProvider();
        final var providerName = provider.name();

        return cache.get(cacheKey)
                .map(cached -> {
                    LOG.debugf(
                            "Translation cache hit: subject=%s, provider=%s, roles=%d, permissions=%d",
                            subject,
                            providerName,
                            cached.roles().size(),
                            cached.permissions().size());
                    metrics.recordCacheHit();
                    return Uni.createFrom().item(cached);
                })
                .orElseGet(() -> {
                    LOG.debugf("Translation cache miss: subject=%s, provider=%s", subject, providerName);
                    metrics.recordCacheMiss();

                    return provider.translate(issuer, subject, claims)
                            .invoke(result -> {
                                cache.put(cacheKey, result);
                                metrics.updateCacheSize(cache.estimatedSize());

                                final var duration = System.currentTimeMillis() - startTime;
                                final var outcome = determineOutcome(result);

                                metrics.recordTranslation(providerName, outcome, duration);

                                LOG.debugf(
                                        "Translation complete: issuer=%s, subject=%s, provider=%s, roles=%s, permissions=%s, duration=%dms",
                                        issuer, subject, providerName, result.roles(), result.permissions(), duration);
                            })
                            .onFailure()
                            .invoke(error -> {
                                final var duration = System.currentTimeMillis() - startTime;
                                metrics.recordTranslation(providerName, TranslationOutcome.ERROR, duration);
                                metrics.recordError(
                                        providerName, error.getClass().getSimpleName());

                                LOG.warnf(
                                        error,
                                        "Translation failed: issuer=%s, subject=%s, provider=%s, duration=%dms",
                                        issuer,
                                        subject,
                                        providerName,
                                        duration);
                            });
                });
    }

    private TranslationOutcome determineOutcome(TranslatedClaims result) {
        if (result.roles().isEmpty() && result.permissions().isEmpty()) {
            return TranslationOutcome.EMPTY;
        }
        return TranslationOutcome.SUCCESS;
    }

    /**
     * Checks if token translation is enabled.
     *
     * @return true if translation is enabled
     */
    public boolean isEnabled() {
        return config.enabled();
    }

    /**
     * Translate token claims using a specific configuration schema.
     *
     * <p>This method does not cache results and is intended for testing configurations.
     *
     * @param schema  the translation configuration to use
     * @param issuer  the token issuer (iss claim)
     * @param subject the token subject (sub claim)
     * @param claims  all claims from the validated token
     * @return translated roles and permissions
     */
    public Uni<TranslatedClaims> translateWithConfig(
            TranslationConfigSchema schema, String issuer, String subject, Map<String, Object> claims) {

        LOG.debugf("Testing translation with config: issuer=%s, subject=%s", issuer, subject);
        return Uni.createFrom().item(ClaimTranslator.translate(schema, claims));
    }

    private String buildCacheKey(String issuer, String subject, Map<String, Object> claims) {
        final var jti = claims.get("jti");
        if (jti != null) {
            return jti.toString();
        }

        final var iat = claims.get("iat");
        final var iatStr = iat != null ? iat.toString() : "0";
        return issuer + ":" + subject + ":" + iatStr;
    }

    // -------------------------------------------------------------------------
    // Introspection Methods
    // -------------------------------------------------------------------------

    /**
     * Get the current cache size.
     *
     * @return estimated number of entries in the cache
     */
    public long getCacheSize() {
        return cache != null ? cache.estimatedSize() : 0;
    }

    /**
     * Get the configured cache TTL in seconds.
     *
     * @return cache TTL in seconds
     */
    public int getCacheTtlSeconds() {
        return config.cache().ttlSeconds();
    }

    /**
     * Get the configured cache max size.
     *
     * @return maximum cache size
     */
    public long getCacheMaxSize() {
        return config.cache().maxSize();
    }

    /**
     * Get the name of the currently active provider.
     *
     * @return provider name
     */
    public String getActiveProviderName() {
        return registry.getProvider().name();
    }

    /**
     * Check if the currently active provider is available and healthy.
     *
     * @return true if the provider is available
     */
    public boolean isProviderHealthy() {
        return registry.getProvider().isAvailable();
    }

    /**
     * Invalidate all cached translation results.
     *
     * <p>This forces re-translation for subsequent requests.
     */
    public void invalidateCache() {
        if (cache != null) {
            cache.invalidateAll();
            metrics.updateCacheSize(0);
            LOG.infof("Token translation cache invalidated");
        }
    }
}
