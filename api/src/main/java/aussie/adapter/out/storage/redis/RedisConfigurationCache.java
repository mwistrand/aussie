package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;

import aussie.core.model.ServiceRegistration;
import aussie.core.port.out.ConfigurationCache;

/**
 * Redis implementation of ConfigurationCache.
 *
 * <p>Provides distributed caching for service registrations with TTL support.
 */
public class RedisConfigurationCache implements ConfigurationCache {

    private static final String KEY_PREFIX = "aussie:config:";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ReactiveValueCommands<String, String> valueCommands;
    private final ReactiveKeyCommands<String> keyCommands;
    private final Duration defaultTtl;

    public RedisConfigurationCache(ReactiveRedisDataSource ds, Duration defaultTtl) {
        this.valueCommands = ds.value(String.class, String.class);
        this.keyCommands = ds.key(String.class);
        this.defaultTtl = defaultTtl;
    }

    @Override
    public Uni<Optional<ServiceRegistration>> get(String serviceId) {
        return valueCommands.get(keyFor(serviceId)).map(json -> {
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(deserialize(json));
        });
    }

    @Override
    public Uni<Void> put(ServiceRegistration registration) {
        return put(registration, defaultTtl);
    }

    @Override
    public Uni<Void> put(ServiceRegistration registration, Duration ttl) {
        String key = keyFor(registration.serviceId());
        String json = serialize(registration);
        return valueCommands.setex(key, ttl.toSeconds(), json).replaceWithVoid();
    }

    @Override
    public Uni<Void> invalidate(String serviceId) {
        return keyCommands.del(keyFor(serviceId)).replaceWithVoid();
    }

    @Override
    public Uni<Void> invalidateAll() {
        // Use SCAN to find and delete all keys with our prefix
        // For safety, we use KEYS pattern match - in production with large datasets,
        // consider using SCAN in batches
        return keyCommands.keys(KEY_PREFIX + "*").flatMap(keys -> {
            if (keys.isEmpty()) {
                return Uni.createFrom().voidItem();
            }
            return keyCommands.del(keys.toArray(new String[0])).replaceWithVoid();
        });
    }

    private String keyFor(String serviceId) {
        return KEY_PREFIX + serviceId;
    }

    private String serialize(ServiceRegistration registration) {
        try {
            return OBJECT_MAPPER.writeValueAsString(registration);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize ServiceRegistration", e);
        }
    }

    private ServiceRegistration deserialize(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, ServiceRegistration.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize ServiceRegistration", e);
        }
    }
}
