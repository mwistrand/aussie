package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.TranslationConfigVersion;
import aussie.core.port.out.TranslationConfigCache;

/**
 * Redis implementation of TranslationConfigCache.
 *
 * <p>Provides distributed caching for translation configurations with TTL support.
 * Used as an intermediate layer between the primary storage (Cassandra) and
 * the local in-memory cache.
 *
 * <h2>Key Structure</h2>
 * <ul>
 *   <li>{@code aussie:translation-config:version:{id}} - Individual version by ID</li>
 *   <li>{@code aussie:translation-config:active} - Currently active version</li>
 *   <li>{@code aussie:translation-config:list} - Cached list of all versions</li>
 * </ul>
 */
public class RedisTranslationConfigCache implements TranslationConfigCache {

    private static final String KEY_PREFIX = "aussie:translation-config:";
    private static final String VERSION_KEY_PREFIX = KEY_PREFIX + "version:";
    private static final String ACTIVE_KEY = KEY_PREFIX + "active";
    private static final String LIST_KEY = KEY_PREFIX + "list";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final TypeReference<List<TranslationConfigVersion>> VERSION_LIST_TYPE = new TypeReference<>() {};

    private static final int SCAN_BATCH_SIZE = 100;

    private final ReactiveRedisDataSource dataSource;
    private final ReactiveValueCommands<String, String> valueCommands;
    private final ReactiveKeyCommands<String> keyCommands;
    private final Duration defaultTtl;

    public RedisTranslationConfigCache(ReactiveRedisDataSource ds, Duration defaultTtl) {
        this.dataSource = ds;
        this.valueCommands = ds.value(String.class, String.class);
        this.keyCommands = ds.key(String.class);
        this.defaultTtl = defaultTtl;
    }

    @Override
    public Uni<Optional<TranslationConfigVersion>> get(String id) {
        return valueCommands.get(versionKey(id)).map(json -> {
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(deserialize(json));
        });
    }

    @Override
    public Uni<Optional<TranslationConfigVersion>> getActive() {
        return valueCommands.get(ACTIVE_KEY).map(json -> {
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(deserialize(json));
        });
    }

    @Override
    public Uni<Void> put(TranslationConfigVersion version) {
        final var key = versionKey(version.id());
        final var json = serialize(version);
        return valueCommands.setex(key, defaultTtl.toSeconds(), json).replaceWithVoid();
    }

    @Override
    public Uni<Void> putActive(TranslationConfigVersion version) {
        final var json = serialize(version);
        return valueCommands.setex(ACTIVE_KEY, defaultTtl.toSeconds(), json).flatMap(v -> put(version));
    }

    @Override
    public Uni<Void> invalidate(String id) {
        return keyCommands.del(versionKey(id)).replaceWithVoid();
    }

    @Override
    public Uni<Void> invalidateActive() {
        return keyCommands.del(ACTIVE_KEY).replaceWithVoid();
    }

    @Override
    public Uni<Void> invalidateAll() {
        // Use SCAN instead of KEYS to avoid blocking Redis with large key sets
        return scanAndDeleteKeys("0");
    }

    /**
     * Recursively scans and deletes keys matching the cache prefix using SCAN.
     * SCAN is safer than KEYS because it doesn't block Redis for the entire operation.
     */
    private Uni<Void> scanAndDeleteKeys(String cursor) {
        return dataSource
                .execute("SCAN", cursor, "MATCH", KEY_PREFIX + "*", "COUNT", String.valueOf(SCAN_BATCH_SIZE))
                .flatMap(response -> {
                    // SCAN returns an array: [cursor, [keys...]]
                    final var newCursor = response.get(0).toString();
                    final var keysResponse = response.get(1);

                    final List<String> keys = new ArrayList<>();
                    for (var i = 0; i < keysResponse.size(); i++) {
                        keys.add(keysResponse.get(i).toString());
                    }

                    // Delete found keys if any
                    final Uni<Void> deleteUni = keys.isEmpty()
                            ? Uni.createFrom().voidItem()
                            : keyCommands.del(keys.toArray(new String[0])).replaceWithVoid();

                    // Continue scanning if cursor is not "0"
                    return deleteUni.flatMap(v -> {
                        if ("0".equals(newCursor)) {
                            return Uni.createFrom().voidItem();
                        }
                        return scanAndDeleteKeys(newCursor);
                    });
                });
    }

    @Override
    public Uni<Optional<List<TranslationConfigVersion>>> getVersionList() {
        return valueCommands.get(LIST_KEY).map(json -> {
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(deserializeList(json));
        });
    }

    @Override
    public Uni<Void> putVersionList(List<TranslationConfigVersion> versions) {
        final var json = serializeList(versions);
        return valueCommands.setex(LIST_KEY, defaultTtl.toSeconds(), json).replaceWithVoid();
    }

    @Override
    public Uni<Void> invalidateVersionList() {
        return keyCommands.del(LIST_KEY).replaceWithVoid();
    }

    private String versionKey(String id) {
        return VERSION_KEY_PREFIX + id;
    }

    private String serialize(TranslationConfigVersion version) {
        try {
            return OBJECT_MAPPER.writeValueAsString(version);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize TranslationConfigVersion", e);
        }
    }

    private TranslationConfigVersion deserialize(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, TranslationConfigVersion.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize TranslationConfigVersion", e);
        }
    }

    private String serializeList(List<TranslationConfigVersion> versions) {
        try {
            return OBJECT_MAPPER.writeValueAsString(versions);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize version list", e);
        }
    }

    private List<TranslationConfigVersion> deserializeList(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, VERSION_LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize version list", e);
        }
    }
}
