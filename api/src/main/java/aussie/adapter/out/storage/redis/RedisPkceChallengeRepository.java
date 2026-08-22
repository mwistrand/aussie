package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.util.Optional;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.jboss.logging.Logger;

import aussie.core.config.PkceConfig;
import aussie.core.model.auth.OidcAuthorizationTransaction;
import aussie.core.port.out.PkceChallengeRepository;

/**
 * Redis implementation of one-time OIDC authorization transaction storage.
 *
 * <p>Transactions are stored as JSON strings with automatic TTL expiration.
 * The {@link #consume} operation uses GETDEL for atomic retrieve-and-delete
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
    public Uni<Void> store(String state, OidcAuthorizationTransaction transaction, Duration ttl) {
        final var key = keyPrefix + state;
        final var value = new JsonObject()
                .put("providerId", transaction.providerId())
                .put("redirectUri", transaction.redirectUri())
                .put("codeChallenge", transaction.codeChallenge())
                .put("nonce", transaction.nonce())
                .put("clientType", transaction.clientType().name())
                .put("createdAt", transaction.createdAt().getEpochSecond())
                .put("expiresAt", transaction.expiresAt().getEpochSecond())
                .encode();

        return valueCommands
                .setex(key, ttl.toSeconds(), value)
                .invoke(() -> LOG.debugf("Stored OIDC authorization transaction with TTL: %s", ttl))
                .replaceWithVoid();
    }

    @Override
    public Uni<Optional<OidcAuthorizationTransaction>> consume(String state) {
        final var key = keyPrefix + state;

        // Use GETDEL for atomic retrieve-and-delete (Redis 6.2+)
        // This ensures the transaction can only be used once
        return valueCommands.getdel(key).map(value -> {
            if (value == null) {
                LOG.debug("No OIDC authorization transaction found");
                return Optional.<OidcAuthorizationTransaction>empty();
            }
            LOG.debug("Consumed OIDC authorization transaction");
            final var json = new JsonObject(value);
            return Optional.of(new OidcAuthorizationTransaction(
                    json.getString("providerId"),
                    json.getString("redirectUri"),
                    json.getString("codeChallenge"),
                    json.getString("nonce"),
                    OidcAuthorizationTransaction.ClientType.valueOf(json.getString("clientType")),
                    java.time.Instant.ofEpochSecond(json.getLong("createdAt")),
                    java.time.Instant.ofEpochSecond(json.getLong("expiresAt"))));
        });
    }
}
