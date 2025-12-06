package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;

import aussie.core.model.ApiKey;
import aussie.core.port.out.AuthKeyCache;

/**
 * Redis implementation of AuthKeyCache.
 *
 * <p>Provides distributed caching for API key authentication lookups with TTL support.
 * Cache entries are keyed by API key hash for efficient lookup during authentication.
 *
 * <h2>Cache Format</h2>
 * <p>Keys are stored with prefix {@code aussie:authkey:} followed by the key hash.
 * Values are serialized API key records with pipe-separated fields.
 */
public class RedisAuthKeyCache implements AuthKeyCache {

    private static final String KEY_PREFIX = "aussie:authkey:";
    private static final String FIELD_SEPARATOR = "|";

    private final ReactiveValueCommands<String, String> valueCommands;
    private final ReactiveKeyCommands<String> keyCommands;
    private final Duration defaultTtl;

    public RedisAuthKeyCache(ReactiveRedisDataSource ds, Duration defaultTtl) {
        this.valueCommands = ds.value(String.class, String.class);
        this.keyCommands = ds.key(String.class);
        this.defaultTtl = defaultTtl;
    }

    @Override
    public Uni<Optional<ApiKey>> get(String keyHash) {
        return valueCommands.get(cacheKey(keyHash)).map(cached -> {
            if (cached == null) {
                return Optional.empty();
            }
            return Optional.of(deserialize(cached, keyHash));
        });
    }

    @Override
    public Uni<Void> put(String keyHash, ApiKey apiKey) {
        String cacheKey = cacheKey(keyHash);
        String serialized = serialize(apiKey);
        return valueCommands.setex(cacheKey, defaultTtl.toSeconds(), serialized).replaceWithVoid();
    }

    @Override
    public Uni<Void> invalidate(String keyHash) {
        return keyCommands.del(cacheKey(keyHash)).replaceWithVoid();
    }

    @Override
    public Uni<Void> invalidateAll() {
        return keyCommands.keys(KEY_PREFIX + "*").flatMap(keys -> {
            if (keys.isEmpty()) {
                return Uni.createFrom().voidItem();
            }
            return keyCommands.del(keys.toArray(new String[0])).replaceWithVoid();
        });
    }

    private String cacheKey(String keyHash) {
        return KEY_PREFIX + keyHash;
    }

    /**
     * Serialize an ApiKey for cache storage.
     *
     * <p>Uses a simple pipe-separated format to minimize cache storage.
     * The keyHash is not stored since it's part of the cache key.
     */
    private String serialize(ApiKey apiKey) {
        return String.join(
                FIELD_SEPARATOR,
                apiKey.id(),
                apiKey.name(),
                apiKey.description() != null ? apiKey.description() : "",
                String.join(",", apiKey.permissions()),
                apiKey.createdAt().toString(),
                apiKey.expiresAt() != null ? apiKey.expiresAt().toString() : "",
                String.valueOf(apiKey.revoked()));
    }

    /**
     * Deserialize an ApiKey from cache.
     *
     * @param cached The cached string representation
     * @param keyHash The key hash (used as cache key)
     */
    private ApiKey deserialize(String cached, String keyHash) {
        String[] parts = cached.split("\\" + FIELD_SEPARATOR, -1);
        if (parts.length < 7) {
            throw new IllegalArgumentException("Invalid cached ApiKey format");
        }

        Set<String> permissions = parts[3].isEmpty() ? Set.of() : Set.of(parts[3].split(","));
        Instant expiresAt = parts[5].isEmpty() ? null : Instant.parse(parts[5]);

        return ApiKey.builder(parts[0], keyHash)
                .name(parts[1])
                .description(parts[2])
                .permissions(permissions)
                .createdAt(Instant.parse(parts[4]))
                .expiresAt(expiresAt)
                .revoked(Boolean.parseBoolean(parts[6]))
                .build();
    }
}
