package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.time.Instant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.arc.DefaultBean;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.KeyScanArgs;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.ResiliencyConfig;
import aussie.core.port.out.Metrics;
import aussie.spi.TokenRevocationRepository;

/**
 * Redis implementation of TokenRevocationRepository.
 *
 * <p>This is the default implementation for production deployments.
 * Revocation entries are stored with automatic TTL expiration.
 *
 * <p>Key format:
 * <ul>
 *   <li>JTI revocation: {@code aussie:revoked:jti:{jti}}</li>
 *   <li>User revocation: {@code aussie:revoked:user:{userId}}</li>
 * </ul>
 *
 * <p>Platform teams can provide custom implementations via CDI:
 * <pre>{@code
 * @Alternative
 * @Priority(1)
 * @ApplicationScoped
 * public class CustomTokenRevocationRepository implements TokenRevocationRepository {
 *     // Custom implementation
 * }
 * }</pre>
 */
@ApplicationScoped
@DefaultBean
public class RedisTokenRevocationRepository implements TokenRevocationRepository {

    private static final Logger LOG = Logger.getLogger(RedisTokenRevocationRepository.class);

    private static final String JTI_PREFIX = "aussie:revoked:jti:";
    private static final String USER_PREFIX = "aussie:revoked:user:";
    private static final String REVOKED_VALUE = "1";

    private final ReactiveValueCommands<String, String> valueCommands;
    private final ReactiveKeyCommands<String> keyCommands;
    private final RedisTimeoutHelper timeoutHelper;

    @Inject
    public RedisTokenRevocationRepository(
            ReactiveRedisDataSource redisDataSource, ResiliencyConfig resiliencyConfig, Metrics metrics) {
        this.valueCommands = redisDataSource.value(String.class, String.class);
        this.keyCommands = redisDataSource.key(String.class);
        this.timeoutHelper = new RedisTimeoutHelper(
                resiliencyConfig.redis().operationTimeout(), metrics, "TokenRevocationRepository");
        LOG.info("Initialized Redis token revocation repository");
    }

    @Override
    public Uni<Void> revoke(String jti, Instant expiresAt) {
        var key = JTI_PREFIX + jti;
        var ttlSeconds = calculateTtl(expiresAt);

        if (ttlSeconds <= 0) {
            LOG.debugf("Skipping revocation for already-expired token: %s", jti);
            return Uni.createFrom().voidItem();
        }

        var operation = valueCommands
                .setex(key, ttlSeconds, REVOKED_VALUE)
                .replaceWithVoid()
                .invoke(() -> LOG.debugf("Revoked token in Redis: %s (TTL: %ds)", jti, ttlSeconds));
        return timeoutHelper.withTimeoutSilent(operation, "revoke");
    }

    @Override
    public Uni<Boolean> isRevoked(String jti) {
        var key = JTI_PREFIX + jti;
        var operation = keyCommands.exists(key);
        // Fail-closed: return true on timeout (treat as revoked for security)
        return timeoutHelper.withTimeoutFallback(operation, "isRevoked", () -> {
            LOG.warnf("Revocation check timed out for jti %s, treating as revoked (fail-closed)", jti);
            return true;
        });
    }

    @Override
    public Uni<Void> revokeAllForUser(String userId, Instant issuedBefore, Instant expiresAt) {
        var key = USER_PREFIX + userId;
        var ttlSeconds = calculateTtl(expiresAt);

        if (ttlSeconds <= 0) {
            LOG.debugf("Skipping user revocation with expired TTL: %s", userId);
            return Uni.createFrom().voidItem();
        }

        // Store the issuedBefore timestamp as the value
        var value = String.valueOf(issuedBefore.toEpochMilli());

        var operation = valueCommands
                .setex(key, ttlSeconds, value)
                .replaceWithVoid()
                .invoke(() -> LOG.debugf(
                        "Revoked all tokens for user in Redis: %s (issuedBefore: %s, TTL: %ds)",
                        userId, issuedBefore, ttlSeconds));
        return timeoutHelper.withTimeoutSilent(operation, "revokeAllForUser");
    }

    @Override
    public Uni<Boolean> isUserRevoked(String userId, Instant issuedAt) {
        var key = USER_PREFIX + userId;

        var operation = valueCommands.get(key).map(value -> {
            if (value == null) {
                return false;
            }

            try {
                var issuedBefore = Instant.ofEpochMilli(Long.parseLong(value));
                return issuedAt.isBefore(issuedBefore);
            } catch (NumberFormatException e) {
                LOG.warnf("Invalid user revocation value for %s: %s", userId, value);
                return false;
            }
        });
        // Fail-closed: return true on timeout (treat as revoked for security)
        return timeoutHelper.withTimeoutFallback(operation, "isUserRevoked", () -> {
            LOG.warnf("User revocation check timed out for %s, treating as revoked (fail-closed)", userId);
            return true;
        });
    }

    @Override
    public Multi<String> streamAllRevokedJtis() {
        var args = new KeyScanArgs().match(JTI_PREFIX + "*").count(1000);
        return keyCommands.scan(args).toMulti().map(key -> key.substring(JTI_PREFIX.length()));
    }

    @Override
    public Multi<String> streamAllRevokedUsers() {
        var args = new KeyScanArgs().match(USER_PREFIX + "*").count(1000);
        return keyCommands.scan(args).toMulti().map(key -> key.substring(USER_PREFIX.length()));
    }

    private long calculateTtl(Instant expiresAt) {
        var ttl = Duration.between(Instant.now(), expiresAt);
        return Math.max(0, ttl.toSeconds());
    }
}
