package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.OidcConfig;
import aussie.core.port.out.OidcRefreshTokenRepository;

/**
 * Redis implementation of OIDC refresh token storage.
 *
 * <p>Refresh tokens are stored with automatic TTL expiration.
 * Key format: {@code aussie:oidc:refresh:{sessionId}}
 */
@ApplicationScoped
public class RedisOidcRefreshTokenRepository implements OidcRefreshTokenRepository {

    private static final Logger LOG = Logger.getLogger(RedisOidcRefreshTokenRepository.class);

    private final ReactiveValueCommands<String, String> valueCommands;
    private final String keyPrefix;

    @Inject
    public RedisOidcRefreshTokenRepository(ReactiveRedisDataSource redisDataSource, OidcConfig config) {
        this.valueCommands = redisDataSource.value(String.class, String.class);
        this.keyPrefix = config.tokenExchange().refreshToken().keyPrefix();
    }

    @Override
    public Uni<Void> store(String sessionId, String refreshToken, Duration ttl) {
        final var key = keyPrefix + sessionId;

        return valueCommands
                .setex(key, ttl.toSeconds(), refreshToken)
                .invoke(() -> LOG.debugf("Stored refresh token for session: %s with TTL: %s", sessionId, ttl))
                .replaceWithVoid();
    }

    @Override
    public Uni<Optional<String>> get(String sessionId) {
        final var key = keyPrefix + sessionId;

        return valueCommands.get(key).map(token -> {
            if (token == null) {
                LOG.debugf("No refresh token found for session: %s", sessionId);
                return Optional.<String>empty();
            }
            LOG.debugf("Retrieved refresh token for session: %s", sessionId);
            return Optional.of(token);
        });
    }

    @Override
    public Uni<Void> delete(String sessionId) {
        final var key = keyPrefix + sessionId;

        return valueCommands
                .getdel(key)
                .invoke(deleted -> {
                    if (deleted != null) {
                        LOG.debugf("Deleted refresh token for session: %s", sessionId);
                    }
                })
                .replaceWithVoid();
    }
}
