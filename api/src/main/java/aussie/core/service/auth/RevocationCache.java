package aussie.core.service.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jboss.logging.Logger;

import aussie.core.config.TokenRevocationConfig;

/**
 * Local LRU cache for confirmed revocations.
 *
 * <p>This is the second tier in the revocation check hierarchy:
 * <ol>
 *   <li>Bloom filter - "definitely not revoked" check</li>
 *   <li>Local cache - confirmed revocations (this class)</li>
 *   <li>Remote store - authoritative source</li>
 * </ol>
 *
 * <p>Entries are cached after a remote lookup confirms revocation,
 * avoiding repeated network calls for the same token.
 *
 * <p>Performance characteristics:
 * <ul>
 *   <li>Lookup: ~1μs (no network I/O)</li>
 *   <li>Memory: configurable max size (default 10,000 entries)</li>
 * </ul>
 */
@ApplicationScoped
public class RevocationCache {

    private static final Logger LOG = Logger.getLogger(RevocationCache.class);

    private final TokenRevocationConfig config;

    private Cache<String, RevocationEntry> jtiCache;
    private Cache<String, UserRevocationEntry> userCache;

    public RevocationCache(TokenRevocationConfig config) {
        this.config = config;
    }

    @PostConstruct
    void init() {
        if (!config.enabled() || !config.cache().enabled()) {
            LOG.info("Revocation cache disabled");
            return;
        }

        var cacheConfig = config.cache();

        this.jtiCache = Caffeine.newBuilder()
                .maximumSize(cacheConfig.maxSize())
                .expireAfterWrite(cacheConfig.ttl().toMillis(), TimeUnit.MILLISECONDS)
                .recordStats()
                .build();

        this.userCache = Caffeine.newBuilder()
                .maximumSize(cacheConfig.maxSize() / 10) // Fewer user revocations expected
                .expireAfterWrite(cacheConfig.ttl().toMillis(), TimeUnit.MILLISECONDS)
                .recordStats()
                .build();

        LOG.infof("Initialized revocation cache (maxSize: %d, ttl: %s)", cacheConfig.maxSize(), cacheConfig.ttl());
    }

    /**
     * Check if a JTI is cached as revoked.
     *
     * @param jti the JWT ID to check
     * @return Optional containing true if cached as revoked, empty if not cached
     */
    public Optional<Boolean> isJtiRevoked(String jti) {
        if (jtiCache == null) {
            return Optional.empty();
        }

        var entry = jtiCache.getIfPresent(jti);
        if (entry != null) {
            // Check if the revocation is still valid
            if (entry.expiresAt().isAfter(Instant.now())) {
                return Optional.of(true);
            }
            // Entry expired, remove it
            jtiCache.invalidate(jti);
        }
        return Optional.empty();
    }

    /**
     * Check if a user has a cached revocation affecting the given issued-at time.
     *
     * @param userId   the user ID to check
     * @param issuedAt when the token was issued
     * @return Optional containing true if token is revoked, empty if not cached
     */
    public Optional<Boolean> isUserRevoked(String userId, Instant issuedAt) {
        if (userCache == null) {
            return Optional.empty();
        }

        var entry = userCache.getIfPresent(userId);
        if (entry != null) {
            // Check if the revocation is still valid and affects this token
            if (entry.expiresAt().isAfter(Instant.now()) && issuedAt.isBefore(entry.issuedBefore())) {
                return Optional.of(true);
            }
        }
        return Optional.empty();
    }

    /**
     * Cache a JTI revocation.
     *
     * @param jti       the revoked JWT ID
     * @param expiresAt when the revocation expires
     */
    public void cacheJtiRevocation(String jti, Instant expiresAt) {
        if (jtiCache != null) {
            jtiCache.put(jti, new RevocationEntry(expiresAt));
            LOG.debugf("Cached JTI revocation: %s (expires: %s)", jti, expiresAt);
        }
    }

    /**
     * Cache a user revocation.
     *
     * @param userId       the user ID
     * @param issuedBefore tokens issued before this time are revoked
     * @param expiresAt    when the revocation expires
     */
    public void cacheUserRevocation(String userId, Instant issuedBefore, Instant expiresAt) {
        if (userCache != null) {
            userCache.put(userId, new UserRevocationEntry(issuedBefore, expiresAt));
            LOG.debugf("Cached user revocation: %s (issuedBefore: %s, expires: %s)", userId, issuedBefore, expiresAt);
        }
    }

    /**
     * Cache a negative result (token is not revoked).
     *
     * <p>This is useful to avoid repeated remote lookups for non-revoked tokens
     * that pass the bloom filter check (false positives).
     *
     * @param jti the JWT ID that is confirmed not revoked
     */
    public void cacheNotRevoked(String jti) {
        // We don't cache negative results in the current implementation
        // since the bloom filter already handles this case efficiently.
        // If we wanted to cache false positives from the bloom filter,
        // we would add a separate cache here.
    }

    /**
     * Invalidate a cached entry.
     *
     * @param jti the JWT ID to invalidate
     */
    public void invalidateJti(String jti) {
        if (jtiCache != null) {
            jtiCache.invalidate(jti);
        }
    }

    /**
     * Invalidate a cached user entry.
     *
     * @param userId the user ID to invalidate
     */
    public void invalidateUser(String userId) {
        if (userCache != null) {
            userCache.invalidate(userId);
        }
    }

    /**
     * Invalidate all cached entries.
     */
    public void invalidateAll() {
        if (jtiCache != null) {
            jtiCache.invalidateAll();
        }
        if (userCache != null) {
            userCache.invalidateAll();
        }
        LOG.debug("Invalidated all revocation cache entries");
    }

    /**
     * Check if the cache is enabled.
     *
     * @return true if cache is enabled and initialized
     */
    public boolean isEnabled() {
        return config.enabled() && config.cache().enabled() && jtiCache != null;
    }

    /**
     * Entry for a JTI revocation.
     */
    private record RevocationEntry(Instant expiresAt) {}

    /**
     * Entry for a user-level revocation.
     */
    private record UserRevocationEntry(Instant issuedBefore, Instant expiresAt) {}
}
