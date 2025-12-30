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
import aussie.core.model.auth.TranslatedClaims;

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
    private LocalCache<String, TranslatedClaims> cache;

    @Inject
    public TokenTranslationService(TokenTranslationConfig config, TokenTranslatorProviderRegistry registry) {
        this.config = config;
        this.registry = registry;
    }

    @PostConstruct
    void init() {
        final var cacheConfig = config.cache();
        this.cache = new CaffeineLocalCache<>(Duration.ofSeconds(cacheConfig.ttlSeconds()), cacheConfig.maxSize());
        LOG.debugf(
                "Token translation cache initialized: ttl=%ds, maxSize=%d",
                cacheConfig.ttlSeconds(), cacheConfig.maxSize());
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

        return cache.get(cacheKey)
                .map(cached -> {
                    LOG.debugf("Translation cache hit for subject: %s", subject);
                    return Uni.createFrom().item(cached);
                })
                .orElseGet(() -> {
                    LOG.debugf("Translation cache miss for subject: %s", subject);
                    return registry.getProvider()
                            .translate(issuer, subject, claims)
                            .invoke(result -> cache.put(cacheKey, result));
                });
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
     * Builds a cache key from token claims.
     *
     * <p>Uses the JWT ID (jti) if present, otherwise creates a composite key
     * from issuer, subject, and issued-at time.
     */
    private String buildCacheKey(String issuer, String subject, Map<String, Object> claims) {
        final var jti = claims.get("jti");
        if (jti != null) {
            return jti.toString();
        }

        // Fall back to composite key
        final var iat = claims.get("iat");
        final var iatStr = iat != null ? iat.toString() : "0";
        return issuer + ":" + subject + ":" + iatStr;
    }
}
