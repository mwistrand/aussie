package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.util.Optional;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.PkceConfig;
import aussie.core.port.out.PkceChallengeRepository;

/**
 * Redis implementation of PKCE challenge storage.
 *
 * <p>Challenges are stored as simple string values with automatic TTL expiration.
 * The {@link #consumeChallenge} operation uses GETDEL for atomic retrieve-and-delete
 * to ensure one-time use.
 *
 * <p>Instances are created by {@link aussie.adapter.out.storage.PkceChallengeRepositoryProducer}.
 */
public class RedisPkceChallengeRepository implements PkceChallengeRepository {

    private static final Logger LOG = Logger.getLogger(RedisPkceChallengeRepository.class);

    private final ReactiveValueCommands<String, String> valueCommands;
    private final String keyPrefix;

    public RedisPkceChallengeRepository(ReactiveRedisDataSource redisDataSource, PkceConfig config) {
        this.valueCommands = redisDataSource.value(String.class, String.class);
        this.keyPrefix = config.storage().redis().keyPrefix();
    }

    @Override
    public Uni<Void> store(String state, String challenge, Duration ttl) {
        final var key = keyPrefix + state;

        return valueCommands
                .setex(key, ttl.toSeconds(), challenge)
                .invoke(() -> LOG.debugf("Stored PKCE challenge for state: %s with TTL: %s", state, ttl))
                .replaceWithVoid();
    }

    @Override
    public Uni<Optional<String>> consumeChallenge(String state) {
        final var key = keyPrefix + state;

        // Use GETDEL for atomic retrieve-and-delete (Redis 6.2+)
        // This ensures the challenge can only be used once
        return valueCommands.getdel(key).map(challenge -> {
            if (challenge == null) {
                LOG.debugf("No PKCE challenge found for state: %s", state);
                return Optional.<String>empty();
            }
            LOG.debugf("Consumed PKCE challenge for state: %s", state);
            return Optional.of(challenge);
        });
    }
}
