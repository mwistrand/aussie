package aussie.core.service.auth;

import java.time.Duration;
import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.TokenRevocationConfig;
import aussie.core.port.out.RevocationEventPublisher;
import aussie.spi.TokenRevocationRepository;

/**
 * Service for token revocation operations.
 *
 * <p>Implements a tiered caching strategy for high-performance revocation checks:
 * <ol>
 *   <li><b>TTL shortcut</b> - Skip check for tokens expiring within threshold</li>
 *   <li><b>Bloom filter</b> - O(1) "definitely not revoked" check (~100ns)</li>
 *   <li><b>Local cache</b> - LRU cache for confirmed revocations (~1μs)</li>
 *   <li><b>Remote store</b> - Authoritative source via SPI (~1-5ms)</li>
 * </ol>
 *
 * <p>Performance targets:
 * <ul>
 *   <li>P50: &lt;100μs (bloom filter hit)</li>
 *   <li>P99: &lt;500μs (local cache hit)</li>
 *   <li>P99.9: &lt;5ms (remote lookup)</li>
 * </ul>
 */
@ApplicationScoped
public class TokenRevocationService {

    private static final Logger LOG = Logger.getLogger(TokenRevocationService.class);
    private static final Duration DEFAULT_REVOCATION_TTL = Duration.ofHours(24);

    private final TokenRevocationConfig config;
    private final TokenRevocationRepository repository;
    private final RevocationEventPublisher eventPublisher;
    private final RevocationBloomFilter bloomFilter;
    private final RevocationCache cache;

    public TokenRevocationService(
            TokenRevocationConfig config,
            TokenRevocationRepository repository,
            RevocationEventPublisher eventPublisher,
            RevocationBloomFilter bloomFilter,
            RevocationCache cache) {
        this.config = config;
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.bloomFilter = bloomFilter;
        this.cache = cache;
    }

    /**
     * Check if a token is revoked.
     *
     * <p>Uses a tiered lookup strategy for optimal performance.
     *
     * @param jti       the JWT ID (may be null if token has no jti claim)
     * @param userId    the user ID (for user-level revocation checks)
     * @param issuedAt  when the token was issued
     * @param expiresAt when the token expires
     * @return Uni with true if revoked, false otherwise
     */
    public Uni<Boolean> isRevoked(String jti, String userId, Instant issuedAt, Instant expiresAt) {
        if (!config.enabled()) {
            return Uni.createFrom().item(false);
        }

        // Tier 0: TTL shortcut - skip check for soon-expiring tokens
        var remainingTtl = Duration.between(Instant.now(), expiresAt);
        if (remainingTtl.compareTo(config.checkThreshold()) < 0) {
            LOG.debugf("Skipping revocation check for token expiring in %s", remainingTtl);
            return Uni.createFrom().item(false);
        }

        final var hasJti = jti != null && !jti.isBlank();

        // Tier 1: Bloom filter - "definitely not revoked" check
        if (bloomFilter.isEnabled()) {
            boolean jtiNotRevoked = !hasJti || bloomFilter.definitelyNotRevoked(jti);
            boolean userNotRevoked = !config.checkUserRevocation() || bloomFilter.userDefinitelyNotRevoked(userId);

            if (jtiNotRevoked && userNotRevoked) {
                LOG.debugf("Bloom filter: token definitely not revoked (jti: %s)", jti);
                return Uni.createFrom().item(false);
            }
        }

        // Tier 2: Local cache check
        if (cache.isEnabled()) {
            if (hasJti) {
                var jtiCached = cache.isJtiRevoked(jti);
                if (jtiCached.isPresent()) {
                    LOG.debugf("Cache hit: JTI revoked (jti: %s)", jti);
                    return Uni.createFrom().item(jtiCached.get());
                }
            }

            if (config.checkUserRevocation()) {
                var userCached = cache.isUserRevoked(userId, issuedAt);
                if (userCached.isPresent()) {
                    LOG.debugf("Cache hit: user revoked (userId: %s)", userId);
                    return Uni.createFrom().item(userCached.get());
                }
            }
        }

        // Tier 3: Remote store lookup
        return checkRemoteStore(jti, userId, issuedAt);
    }

    private Uni<Boolean> checkRemoteStore(String jti, String userId, Instant issuedAt) {
        LOG.debugf("Remote lookup for revocation (jti: %s, userId: %s)", jti, userId);

        final var hasJti = jti != null && !jti.isBlank();

        // Check JTI revocation only if JTI is present
        Uni<Boolean> jtiCheck = hasJti
                ? repository.isRevoked(jti).map(jtiRevoked -> {
                    if (jtiRevoked) {
                        cache.cacheJtiRevocation(
                                jti, Instant.now().plus(config.cache().ttl()));
                    }
                    return jtiRevoked;
                })
                : Uni.createFrom().item(false);

        return jtiCheck.flatMap(jtiRevoked -> {
            if (jtiRevoked) {
                return Uni.createFrom().item(true);
            }

            // Check user-level revocation if enabled
            if (!config.checkUserRevocation()) {
                return Uni.createFrom().item(false);
            }

            return repository.isUserRevoked(userId, issuedAt).map(userRevoked -> {
                if (userRevoked) {
                    cache.cacheUserRevocation(
                            userId, issuedAt, Instant.now().plus(config.cache().ttl()));
                }
                return userRevoked;
            });
        });
    }

    /**
     * Revoke a specific token.
     *
     * @param jti       the JWT ID to revoke
     * @param expiresAt when the token expires (revocation entry will auto-cleanup), or null to use default TTL
     * @return Uni completing when revocation is stored and published
     */
    public Uni<Void> revokeToken(String jti, Instant expiresAt) {
        var effectiveExpiresAt = expiresAt != null ? expiresAt : Instant.now().plus(DEFAULT_REVOCATION_TTL);
        if (!config.enabled()) {
            LOG.warn("Token revocation is disabled, ignoring revoke request");
            return Uni.createFrom().voidItem();
        }

        LOG.infof("Revoking token: %s (expires: %s)", jti, effectiveExpiresAt);

        return repository
                .revoke(jti, effectiveExpiresAt)
                .invoke(() -> {
                    bloomFilter.addRevokedJti(jti);
                    cache.cacheJtiRevocation(jti, effectiveExpiresAt);
                })
                .flatMap(v -> {
                    if (config.pubsub().enabled()) {
                        return eventPublisher.publishJtiRevoked(jti, effectiveExpiresAt);
                    }
                    return Uni.createFrom().voidItem();
                })
                .invoke(() -> LOG.debugf("Token revocation completed: %s", jti));
    }

    /**
     * Revoke all tokens for a user issued before now.
     *
     * <p>This is typically used for "logout everywhere" functionality.
     *
     * @param userId the user whose tokens should be revoked
     * @return Uni completing when revocation is stored and published
     */
    public Uni<Void> revokeAllUserTokens(String userId) {
        return revokeAllUserTokens(userId, Instant.now());
    }

    /**
     * Revoke all tokens for a user issued before a specific time.
     *
     * @param userId       the user whose tokens should be revoked
     * @param issuedBefore tokens issued before this time are revoked
     * @return Uni completing when revocation is stored and published
     */
    public Uni<Void> revokeAllUserTokens(String userId, Instant issuedBefore) {
        if (!config.enabled()) {
            LOG.warn("Token revocation is disabled, ignoring user revoke request");
            return Uni.createFrom().voidItem();
        }

        if (!config.checkUserRevocation()) {
            LOG.warn("User-level revocation is disabled");
            return Uni.createFrom().voidItem();
        }

        LOG.infof("Revoking all tokens for user: %s (issuedBefore: %s)", userId, issuedBefore);

        // Calculate expiry for user revocation entry
        // Should be longer than max token TTL to cover all affected tokens
        var expiresAt = Instant.now().plus(Duration.ofDays(1));

        return repository
                .revokeAllForUser(userId, issuedBefore, expiresAt)
                .invoke(() -> {
                    // Update local bloom filter
                    bloomFilter.addRevokedUser(userId);
                    // Update local cache
                    cache.cacheUserRevocation(userId, issuedBefore, expiresAt);
                })
                .flatMap(v -> {
                    // Publish event to other instances
                    if (config.pubsub().enabled()) {
                        return eventPublisher.publishUserRevoked(userId, issuedBefore, expiresAt);
                    }
                    return Uni.createFrom().voidItem();
                })
                .invoke(() -> LOG.debugf("User token revocation completed: %s", userId));
    }

    /**
     * Trigger a rebuild of the bloom filter from the remote store.
     *
     * @return Uni completing when rebuild is done
     */
    public Uni<Void> rebuildBloomFilter() {
        return bloomFilter.rebuildFilters();
    }

    /**
     * Invalidate all local caches.
     */
    public void invalidateCaches() {
        cache.invalidateAll();
        LOG.info("Invalidated all revocation caches");
    }

    /**
     * Check if revocation is enabled.
     *
     * @return true if token revocation is enabled
     */
    public boolean isEnabled() {
        return config.enabled();
    }

    /**
     * Check if a specific token is revoked (for admin queries).
     *
     * @param jti the JWT ID to check
     * @return Uni with true if revoked, false otherwise
     */
    public Uni<Boolean> isTokenRevoked(String jti) {
        return repository.isRevoked(jti);
    }

    /**
     * Stream all revoked JTIs (for admin queries).
     *
     * @return Multi of revoked JTIs
     */
    public Multi<String> streamAllRevokedJtis() {
        return repository.streamAllRevokedJtis();
    }

    /**
     * Stream all users with blanket revocations (for admin queries).
     *
     * @return Multi of user IDs
     */
    public Multi<String> streamAllRevokedUsers() {
        return repository.streamAllRevokedUsers();
    }
}
