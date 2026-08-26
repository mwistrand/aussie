package aussie.adapter.out.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import io.quarkus.runtime.LaunchMode;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.lang.JoseException;

import aussie.adapter.out.http.UpstreamAddressResolver;
import aussie.core.config.ResiliencyConfig;
import aussie.core.port.out.JwksCache;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.OutboundHttpClients;
import aussie.core.service.auth.JwksFetchException;

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
    private static final Set<String> PERMITTED_ALGORITHMS =
            Set.of("RS256", "RS384", "RS512", "ES256", "ES384", "ES512", "EdDSA");

    private final WebClient webClient;
    private final Cache<URI, CachedKeySet> cache;
    private final Map<URI, Uni<JsonWebKeySet>> inFlightFetches = new ConcurrentHashMap<>();
    private final ResiliencyConfig.JwksConfig jwksConfig;
    private final Metrics metrics;
    private final UpstreamAddressResolver addressResolver;

    @Inject
    public JwksCacheService(
            OutboundHttpClients outboundClient,
            ResiliencyConfig resiliencyConfig,
            MeterRegistry meterRegistry,
            Metrics metrics,
            UpstreamAddressResolver addressResolver) {
        this.webClient = outboundClient.jwksWebClient();
        this.jwksConfig = resiliencyConfig.jwks();
        this.metrics = metrics;
        this.addressResolver = addressResolver;
        validateConfiguration();

        // Build bounded Caffeine cache with LRU eviction
        this.cache = Caffeine.newBuilder()
                .maximumSize(jwksConfig.maxCacheEntries())
                .expireAfterWrite(jwksConfig.cacheTtl().plus(jwksConfig.maximumStale()))
                .recordStats()
                .build();

        // Register cache metrics with Micrometer
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "aussie.jwks.cache");
    }

    @Override
    public Uni<JsonWebKeySet> getKeySet(URI jwksUri) {
        validateUri(jwksUri);
        var cached = cache.getIfPresent(jwksUri);
        if (cached != null && cached.isFresh()) {
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
        validateUri(jwksUri);
        LOG.infov("Force refreshing JWKS for host {0}", jwksUri.getHost());
        cache.invalidate(jwksUri);
        inFlightFetches.remove(jwksUri); // Clear any stale in-flight fetch
        return getOrCreateFetch(jwksUri);
    }

    @Override
    public void invalidate(URI jwksUri) {
        LOG.infov("Invalidating cached JWKS for host {0}", jwksUri.getHost());
        cache.invalidate(jwksUri);
        inFlightFetches.remove(jwksUri);
    }

    private Uni<JsonWebKeySet> fetchAndCache(URI jwksUri) {
        LOG.infov("Fetching JWKS from host {0}", jwksUri.getHost());

        return addressResolver
                .resolve(jwksUri)
                .flatMap(serverAddress -> webClient
                        .requestAbs(
                                HttpMethod.GET,
                                io.vertx.mutiny.core.net.SocketAddress.newInstance(serverAddress),
                                jwksUri.toString())
                        .ssl("https".equalsIgnoreCase(jwksUri.getScheme()))
                        .followRedirects(false)
                        .send())
                .ifNoItem()
                .after(jwksConfig.fetchTimeout())
                .failWith(() -> {
                    LOG.warnv(
                            "JWKS fetch timeout for host {0} after {1}", jwksUri.getHost(), jwksConfig.fetchTimeout());
                    metrics.recordJwksFetchTimeout(jwksUri.getHost());
                    return new JwksFetchException("Timeout fetching JWKS");
                })
                .map(this::parseResponse)
                .invoke(keySet -> {
                    final var expiresAt = Instant.now().plus(jwksConfig.cacheTtl());
                    cache.put(jwksUri, new CachedKeySet(keySet, expiresAt, expiresAt.plus(jwksConfig.maximumStale())));
                    LOG.infov(
                            "Cached {0} keys from host {1}",
                            keySet.getJsonWebKeys().size(), jwksUri.getHost());
                })
                .onFailure()
                .recoverWithUni(error -> {
                    LOG.errorv(error, "Failed to fetch JWKS from host {0}", jwksUri.getHost());
                    // Try to use stale cached keys if available
                    var stale = cache.getIfPresent(jwksUri);
                    if (stale != null && stale.canUseStale()) {
                        LOG.warnv("Using stale cached JWKS for host {0}", jwksUri.getHost());
                        return Uni.createFrom().item(stale.keySet());
                    }
                    return Uni.createFrom().failure(error);
                });
    }

    private JsonWebKeySet parseResponse(HttpResponse<Buffer> response) {
        if (response.statusCode() != 200) {
            throw new JwksFetchException("JWKS endpoint returned status " + response.statusCode());
        }

        final var contentType = response.getHeader("Content-Type");
        final var mediaType = contentType == null ? "" : contentType.split(";", 2)[0].trim();
        if (!(mediaType.equalsIgnoreCase("application/json")
                || mediaType.equalsIgnoreCase("application/jwk-set+json"))) {
            throw new JwksFetchException("JWKS endpoint returned an unsupported content type");
        }

        final var contentLength = response.getHeader("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > jwksConfig.maxResponseBytes()) {
                    throw new JwksFetchException("JWKS response exceeds the configured size limit");
                }
            } catch (NumberFormatException e) {
                throw new JwksFetchException("JWKS endpoint returned an invalid content length", e);
            }
        }

        final var body = response.bodyAsString();
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > jwksConfig.maxResponseBytes()) {
            throw new JwksFetchException("JWKS response exceeds the configured size limit");
        }
        try {
            final var keySet = new JsonWebKeySet(body);
            validateKeys(keySet);
            return keySet;
        } catch (JoseException e) {
            throw new JwksFetchException("Failed to parse JWKS response", e);
        }
    }

    private void validateKeys(JsonWebKeySet keySet) {
        final var keys = keySet.getJsonWebKeys();
        if (keys.size() > jwksConfig.maxKeys()) {
            throw new JwksFetchException("JWKS contains too many keys");
        }

        final var keyIds = new HashSet<String>();
        for (final var key : keys) {
            if (!keyIds.add(key.getKeyId())) {
                throw new JwksFetchException("JWKS contains duplicate key IDs");
            }
            if (key.getUse() != null && !"sig".equals(key.getUse())) {
                throw new JwksFetchException("JWKS contains a key not permitted for signatures");
            }
            if (key.getKeyOps() != null
                    && !key.getKeyOps().isEmpty()
                    && !key.getKeyOps().contains("verify")) {
                throw new JwksFetchException("JWKS contains a key not permitted for verification");
            }
            if (key.getAlgorithm() != null && !PERMITTED_ALGORITHMS.contains(key.getAlgorithm())) {
                throw new JwksFetchException("JWKS contains a key with a prohibited algorithm");
            }

            final var publicKey = key.getPublicKey();
            if (publicKey instanceof RSAPublicKey rsaKey && rsaKey.getModulus().bitLength() >= 2048) {
                continue;
            }
            if (publicKey instanceof ECPublicKey ecKey
                    && ecKey.getParams().getCurve().getField().getFieldSize() >= 256) {
                continue;
            }
            if (publicKey != null
                    && ("EdDSA".equals(publicKey.getAlgorithm()) || "Ed25519".equals(publicKey.getAlgorithm()))) {
                continue;
            }
            throw new JwksFetchException("JWKS contains an unsupported or undersized public key");
        }
    }

    private void validateConfiguration() {
        if (jwksConfig.maxCacheEntries() < 1
                || jwksConfig.maxResponseBytes() < 1
                || jwksConfig.maxKeys() < 1
                || jwksConfig.cacheTtl().isZero()
                || jwksConfig.cacheTtl().isNegative()
                || jwksConfig.maximumStale().isNegative()) {
            throw new IllegalArgumentException(
                    "JWKS cache limits must be positive and maximum-stale cannot be negative");
        }
    }

    private void validateUri(URI jwksUri) {
        final var activeProfiles =
                ConfigProvider.getConfig().unwrap(SmallRyeConfig.class).getProfiles();
        validateUri(jwksUri, LaunchMode.current(), activeProfiles);
    }

    static void validateUri(URI jwksUri, LaunchMode launchMode, List<String> activeProfiles) {
        if (jwksUri == null
                || jwksUri.getHost() == null
                || (launchMode == LaunchMode.NORMAL
                        && !activeProfiles.contains("dev")
                        && !activeProfiles.contains("test")
                        && !"https".equalsIgnoreCase(jwksUri.getScheme()))) {
            throw new JwksFetchException("JWKS URI must use HTTPS outside dev/test");
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

    private record CachedKeySet(JsonWebKeySet keySet, Instant expiresAt, Instant staleUntil) {
        boolean isFresh() {
            return Instant.now().isBefore(expiresAt);
        }

        boolean canUseStale() {
            return !Instant.now().isAfter(staleUntil);
        }
    }
}
