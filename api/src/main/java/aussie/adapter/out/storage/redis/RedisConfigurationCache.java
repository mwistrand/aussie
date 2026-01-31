package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;

import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.ConfigurationCache;

/**
 * Redis implementation of ConfigurationCache.
 *
 * <p>Provides distributed caching for service registrations with TTL support.
 */
public class RedisConfigurationCache implements ConfigurationCache {

    private static final String KEY_PREFIX = "aussie:config:";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ReactiveValueCommands<String, String> valueCommands;
    private final ReactiveKeyCommands<String> keyCommands;
    private final Duration defaultTtl;
    private final RedisTimeoutHelper timeoutHelper;

    public RedisConfigurationCache(ReactiveRedisDataSource ds, Duration defaultTtl, RedisTimeoutHelper timeoutHelper) {
        this.valueCommands = ds.value(String.class, String.class);
        this.keyCommands = ds.key(String.class);
        this.defaultTtl = defaultTtl;
        this.timeoutHelper = timeoutHelper;
    }

    @Override
    public Uni<Optional<ServiceRegistration>> get(String serviceId) {
        var operation = valueCommands.get(keyFor(serviceId)).map(json -> {
            if (json == null) {
                return null;
            }
            return deserialize(json);
        });
        return timeoutHelper.withTimeoutGraceful(operation, "get");
    }

    @Override
    public Uni<Void> put(ServiceRegistration registration) {
        return put(registration, defaultTtl);
    }

    @Override
    public Uni<Void> put(ServiceRegistration registration, Duration ttl) {
        String key = keyFor(registration.serviceId());
        String json = serialize(registration);
        var operation = valueCommands.setex(key, ttl.toSeconds(), json).replaceWithVoid();
        return timeoutHelper.withTimeoutSilent(operation, "put");
    }

    @Override
    public Uni<Void> invalidate(String serviceId) {
        var operation = keyCommands.del(keyFor(serviceId)).replaceWithVoid();
        return timeoutHelper.withTimeoutSilent(operation, "invalidate");
    }

    @Override
    public Uni<Void> invalidateAll() {
        // Use SCAN to find and delete all keys with our prefix
        // For safety, we use KEYS pattern match - in production with large datasets,
        // consider using SCAN in batches
        var operation = keyCommands.keys(KEY_PREFIX + "*").flatMap(keys -> {
            if (keys.isEmpty()) {
                return Uni.createFrom().voidItem();
            }
            return keyCommands.del(keys.toArray(new String[0])).replaceWithVoid();
        });
        return timeoutHelper.withTimeoutSilent(operation, "invalidateAll");
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
