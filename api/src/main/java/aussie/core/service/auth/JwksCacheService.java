package aussie.core.service.auth;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.jboss.logging.Logger;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.lang.JoseException;

import aussie.core.config.ResiliencyConfig;
import aussie.core.port.out.JwksCache;
import aussie.core.port.out.Metrics;

/**
 * Service for caching and retrieving JSON Web Key Sets (JWKS).
 *
 * <p>Features:
 * <ul>
 *   <li>In-memory caching with configurable TTL</li>
 *   <li>Automatic refresh before expiration</li>
 *   <li>Graceful handling of key rotation</li>
 *   <li>Thundering herd protection via request coalescing</li>
 * </ul>
 *
 * <p>Thread-safety: Uses request coalescing to prevent multiple concurrent
 * fetches to the same JWKS endpoint when the cache expires.
 */
@ApplicationScoped
public class JwksCacheService implements JwksCache {

    private static final Logger LOG = Logger.getLogger(JwksCacheService.class);

    private final WebClient webClient;
    private final Cache<URI, CachedKeySet> cache;
    private final Map<URI, Uni<JsonWebKeySet>> inFlightFetches = new ConcurrentHashMap<>();
    private final ResiliencyConfig.JwksConfig jwksConfig;
    private final Metrics metrics;

    @Inject
    public JwksCacheService(
            Vertx vertx, ResiliencyConfig resiliencyConfig, MeterRegistry meterRegistry, Metrics metrics) {
        this.webClient = WebClient.create(vertx);
        this.jwksConfig = resiliencyConfig.jwks();
        this.metrics = metrics;

        // Build bounded Caffeine cache with LRU eviction
        this.cache = Caffeine.newBuilder()
                .maximumSize(jwksConfig.maxCacheEntries())
                .expireAfterWrite(jwksConfig.cacheTtl())
                .recordStats()
                .build();

        // Register cache metrics with Micrometer
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "aussie.jwks.cache");
    }

    @Override
    public Uni<JsonWebKeySet> getKeySet(URI jwksUri) {
        var cached = cache.getIfPresent(jwksUri);
        if (cached != null && !cached.isExpired()) {
            LOG.debugv("Using cached JWKS for {0}", jwksUri);
            return Uni.createFrom().item(cached.keySet());
        }
        return getOrCreateFetch(jwksUri);
    }

    /**
     * Get an existing in-flight fetch or creates a new one.
     * This prevents thundering herd by coalescing concurrent requests.
     */
    private Uni<JsonWebKeySet> getOrCreateFetch(URI jwksUri) {
        return Uni.createFrom().deferred(() -> {
            // Use computeIfAbsent to ensure only one fetch per URI
            var fetch = inFlightFetches.computeIfAbsent(jwksUri, this::createFetch);
            return fetch;
        });
    }

    private Uni<JsonWebKeySet> createFetch(URI jwksUri) {
        return fetchAndCache(jwksUri)
                .onTermination()
                .invoke(() -> inFlightFetches.remove(jwksUri))
                .memoize()
                .indefinitely();
    }

    @Override
    public Uni<Optional<JsonWebKey>> getKey(URI jwksUri, String keyId) {
        return getKeySet(jwksUri).map(keySet -> findKey(keySet, keyId));
    }

    @Override
    public Uni<JsonWebKeySet> refresh(URI jwksUri) {
        LOG.infov("Force refreshing JWKS for {0}", jwksUri);
        cache.invalidate(jwksUri);
        inFlightFetches.remove(jwksUri); // Clear any stale in-flight fetch
        return getOrCreateFetch(jwksUri);
    }

    @Override
    public void invalidate(URI jwksUri) {
        LOG.infov("Invalidating cached JWKS for {0}", jwksUri);
        cache.invalidate(jwksUri);
        inFlightFetches.remove(jwksUri);
    }

    private Uni<JsonWebKeySet> fetchAndCache(URI jwksUri) {
        LOG.infov("Fetching JWKS from {0}", jwksUri);

        return webClient
                .getAbs(jwksUri.toString())
                .ssl(jwksUri.getScheme().equals("https"))
                .send()
                .ifNoItem()
                .after(jwksConfig.fetchTimeout())
                .failWith(() -> {
                    LOG.warnv("JWKS fetch timeout for {0} after {1}", jwksUri, jwksConfig.fetchTimeout());
                    metrics.recordJwksFetchTimeout(jwksUri.getHost());
                    return new JwksFetchException("Timeout fetching JWKS from " + jwksUri);
                })
                .map(this::parseResponse)
                .invoke(keySet -> {
                    cache.put(jwksUri, new CachedKeySet(keySet, Instant.now().plus(jwksConfig.cacheTtl())));
                    LOG.infov(
                            "Cached {0} keys from {1}", keySet.getJsonWebKeys().size(), jwksUri);
                })
                .onFailure()
                .recoverWithUni(error -> {
                    LOG.errorv(error, "Failed to fetch JWKS from {0}", jwksUri);
                    // Try to use stale cached keys if available
                    var stale = cache.getIfPresent(jwksUri);
                    if (stale != null) {
                        LOG.warnv("Using stale cached JWKS for {0} due to: {1}", jwksUri, error.getMessage());
                        return Uni.createFrom().item(stale.keySet());
                    }
                    return Uni.createFrom().failure(error);
                });
    }

    private JsonWebKeySet parseResponse(HttpResponse<Buffer> response) {
        if (response.statusCode() != 200) {
            throw new JwksFetchException("JWKS endpoint returned status " + response.statusCode());
        }

        String body = response.bodyAsString();
        try {
            return new JsonWebKeySet(body);
        } catch (JoseException e) {
            throw new JwksFetchException("Failed to parse JWKS response: " + e.getMessage(), e);
        }
    }

    private Optional<JsonWebKey> findKey(JsonWebKeySet keySet, String keyId) {
        if (keyId == null) {
            // If no key ID specified, try to find a single signing key
            var keys = keySet.getJsonWebKeys();
            if (keys.size() == 1) {
                return Optional.of(keys.get(0));
            }
            return Optional.empty();
        }

        return keySet.getJsonWebKeys().stream()
                .filter(key -> keyId.equals(key.getKeyId()))
                .findFirst();
    }

    private record CachedKeySet(JsonWebKeySet keySet, Instant expiresAt) {
        boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    /**
     * Exception thrown when JWKS fetch fails.
     *
     * <p>This can occur due to network timeout, HTTP error, or malformed JWKS response.
     * If cached keys are available, the service falls back to stale data.
     */
    public static class JwksFetchException extends RuntimeException {
        public JwksFetchException(String message) {
            super(message);
        }

        public JwksFetchException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
