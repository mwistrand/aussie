package aussie.adapter.out.storage.redis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.SessionConfig;
import aussie.core.model.session.Session;
import aussie.core.port.out.SessionRepository;
import aussie.core.util.SecureHash;

/** Redis implementation of SessionRepository. */
public class RedisSessionRepository implements SessionRepository {

    private static final Logger LOG = Logger.getLogger(RedisSessionRepository.class);
    private static final String USER_INDEX_PREFIX = "aussie:session:user:";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_RECORD_BYTES = 64 * 1024;

    static final String SAVE_SCRIPT = """
            local result = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[3])
            if not result then return 0 end
            redis.call('SADD', KEYS[2], ARGV[2])
            redis.call('EXPIRE', KEYS[2], ARGV[4])
            return 1
            """;

    static final String REPLACE_SCRIPT = """
            redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3])
            redis.call('SADD', KEYS[2], ARGV[2])
            redis.call('EXPIRE', KEYS[2], ARGV[4])
            return 1
            """;

    static final String MIGRATE_SCRIPT = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[4])
            redis.call('SADD', KEYS[2], ARGV[3])
            redis.call('EXPIRE', KEYS[2], ARGV[5])
            return 1
            """;

    static final String UPDATE_SCRIPT = """
            local ttl = redis.call('TTL', KEYS[1])
            if ttl == -2 then return 0 end
            if ttl < 1 then ttl = ARGV[2] end
            redis.call('SET', KEYS[1], ARGV[1], 'XX', 'EX', ttl)
            return 1
            """;

    static final String DELETE_SCRIPT = """
            redis.call('DEL', KEYS[1])
            redis.call('SREM', KEYS[2], ARGV[1])
            return 1
            """;

    static final String DELETE_BY_USER_SCRIPT = """
            local ids = redis.call('SMEMBERS', KEYS[1])
            local legacy = redis.call('GET', KEYS[2])
            if legacy then
                for id in string.gmatch(legacy, '([^,]+)') do
                    id = string.match(id, '^%s*(.-)%s*$')
                    if id ~= '' then table.insert(ids, id) end
                end
            end
            for _, id in ipairs(ids) do redis.call('DEL', ARGV[1] .. id) end
            redis.call('DEL', KEYS[1], KEYS[2])
            return #ids
            """;

    private final ReactiveRedisDataSource redisDataSource;
    private final ReactiveValueCommands<String, String> valueCommands;
    private final ReactiveKeyCommands<String> keyCommands;
    private final String keyPrefix;
    private final Duration sessionTtl;
    private final RedisTimeoutHelper timeoutHelper;
    private final ObjectMapper objectMapper;

    public RedisSessionRepository(
            ReactiveRedisDataSource redisDataSource,
            SessionConfig config,
            RedisTimeoutHelper timeoutHelper,
            ObjectMapper objectMapper) {
        this.redisDataSource = redisDataSource;
        this.valueCommands = redisDataSource.value(String.class, String.class);
        this.keyCommands = redisDataSource.key(String.class);
        this.keyPrefix = config.storage().redis().keyPrefix();
        this.sessionTtl = config.ttl();
        this.timeoutHelper = timeoutHelper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Uni<Boolean> saveIfAbsent(Session session) {
        final var operation = redisDataSource
                .execute(
                        "EVAL",
                        SAVE_SCRIPT,
                        "2",
                        sessionKey(session.id()),
                        userIndexKey(session.userId()),
                        serialize(session),
                        session.id(),
                        String.valueOf(ttlSeconds()),
                        String.valueOf(indexTtlSeconds()))
                .map(result -> result.toLong() == 1)
                .invoke(saved -> LOG.debugf(
                        "Session %s in Redis: hash=%s",
                        saved ? "created" : "already exists", SecureHash.truncatedSha256(session.id(), 8)));
        return timeoutHelper.withTimeout(operation, "saveIfAbsent");
    }

    @Override
    public Uni<Session> save(Session session) {
        final var operation = redisDataSource
                .execute(
                        "EVAL",
                        REPLACE_SCRIPT,
                        "2",
                        sessionKey(session.id()),
                        userIndexKey(session.userId()),
                        serialize(session),
                        session.id(),
                        String.valueOf(ttlSeconds()),
                        String.valueOf(indexTtlSeconds()))
                .replaceWith(session);
        return timeoutHelper.withTimeout(operation, "save");
    }

    @Override
    public Uni<Optional<Session>> findById(String sessionId) {
        final var key = sessionKey(sessionId);
        final var operation = valueCommands.get(key).flatMap(value -> {
            if (value == null) {
                return Uni.createFrom().item(Optional.<Session>empty());
            }
            final var session = deserialize(value);
            if (!isLegacy(value)) {
                return Uni.createFrom().item(Optional.of(session));
            }
            return keyCommands.ttl(key).flatMap(ttl -> redisDataSource
                    .execute(
                            "EVAL",
                            MIGRATE_SCRIPT,
                            "2",
                            key,
                            userIndexKey(session.userId()),
                            value,
                            serialize(session),
                            session.id(),
                            String.valueOf(ttl > 0 ? ttl : ttlSeconds()),
                            String.valueOf(indexTtlSeconds()))
                    .map(result -> result.toLong() == 1 ? Optional.of(session) : Optional.<Session>empty()));
        });
        return timeoutHelper.withTimeout(operation, "findById");
    }

    @Override
    public Uni<Session> update(Session session) {
        final var operation = redisDataSource
                .execute(
                        "EVAL",
                        UPDATE_SCRIPT,
                        "1",
                        sessionKey(session.id()),
                        serialize(session),
                        String.valueOf(ttlSeconds()))
                .replaceWith(session);
        return timeoutHelper.withTimeout(operation, "update");
    }

    @Override
    public Uni<Void> delete(String sessionId) {
        final var operation = findById(sessionId)
                .flatMap(session -> session.isPresent()
                        ? redisDataSource
                                .execute(
                                        "EVAL",
                                        DELETE_SCRIPT,
                                        "2",
                                        sessionKey(sessionId),
                                        userIndexKey(session.get().userId()),
                                        sessionId)
                                .replaceWithVoid()
                        : keyCommands.del(sessionKey(sessionId)).replaceWithVoid());
        return timeoutHelper.withTimeout(operation, "delete");
    }

    @Override
    public Uni<Void> deleteByUserId(String userId) {
        final var operation = redisDataSource
                .execute(
                        "EVAL", DELETE_BY_USER_SCRIPT, "2", userIndexKey(userId), legacyUserIndexKey(userId), keyPrefix)
                .replaceWithVoid();
        return timeoutHelper.withTimeout(operation, "deleteByUserId");
    }

    @Override
    public Uni<Boolean> exists(String sessionId) {
        return timeoutHelper.withTimeout(keyCommands.exists(sessionKey(sessionId)), "exists");
    }

    private String sessionKey(String sessionId) {
        return keyPrefix + sessionId;
    }

    private String userIndexKey(String userId) {
        return USER_INDEX_PREFIX + SecureHash.truncatedSha256(userId, 64);
    }

    private String legacyUserIndexKey(String userId) {
        return USER_INDEX_PREFIX + userId;
    }

    private long ttlSeconds() {
        return Math.max(1, sessionTtl.toSeconds());
    }

    private long indexTtlSeconds() {
        return Math.max(1, ttlSeconds() * 2);
    }

    private String serialize(Session session) {
        return serialize(objectMapper, session);
    }

    static String serialize(ObjectMapper objectMapper, Session session) {
        try {
            final var node = objectMapper.createObjectNode();
            node.put("id", session.id());
            node.put("userId", session.userId());
            putNullable(node, objectMapper, "issuer", session.issuer());
            node.set("claims", objectMapper.valueToTree(session.claims()));
            node.set("permissions", objectMapper.valueToTree(session.permissions()));
            node.set("createdAt", objectMapper.valueToTree(session.createdAt()));
            node.set("expiresAt", objectMapper.valueToTree(session.expiresAt()));
            node.set("lastAccessedAt", objectMapper.valueToTree(session.lastAccessedAt()));
            putNullable(node, objectMapper, "userAgent", session.userAgent());
            putNullable(node, objectMapper, "ipAddress", session.ipAddress());
            node.put("schemaVersion", SCHEMA_VERSION);
            final var value = objectMapper.writeValueAsString(node);
            checkSize(value);
            return value;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize session", e);
        }
    }

    private static void putNullable(ObjectNode node, ObjectMapper mapper, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private Session deserialize(String value) {
        return deserialize(objectMapper, value);
    }

    static Session deserialize(ObjectMapper objectMapper, String value) {
        checkSize(value);
        if (isLegacy(value)) {
            return deserializeLegacy(value);
        }
        try {
            final JsonNode node = objectMapper.readTree(value);
            if (node.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported session schema version");
            }
            ((ObjectNode) node).remove("schemaVersion");
            return objectMapper.treeToValue(node, Session.class);
        } catch (JsonProcessingException | ClassCastException e) {
            throw new IllegalArgumentException("Invalid session record", e);
        }
    }

    private static boolean isLegacy(String value) {
        return !value.trim().startsWith("{");
    }

    private static Session deserializeLegacy(String value) {
        final var parts = value.split("\\|", -1);
        if (parts.length < 10) {
            throw new IllegalArgumentException("Invalid legacy session format");
        }
        return new Session(
                parts[0],
                parts[1],
                parts[2].isEmpty() ? null : parts[2],
                deserializeLegacyClaims(parts[3]),
                deserializeLegacyPermissions(parts[4]),
                Instant.ofEpochMilli(Long.parseLong(parts[5])),
                parts[6].isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(parts[6])),
                parts[7].isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(parts[7])),
                parts[8].isEmpty() ? null : parts[8],
                parts[9].isEmpty() ? null : parts[9]);
    }

    private static Map<String, Object> deserializeLegacyClaims(String value) {
        final var claims = new HashMap<String, Object>();
        if (value == null || value.isEmpty()) {
            return claims;
        }
        for (final var pair : value.split(";")) {
            final var keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                claims.put(keyValue[0], keyValue[1]);
            }
        }
        return claims;
    }

    private static Set<String> deserializeLegacyPermissions(String value) {
        final var permissions = new HashSet<String>();
        if (value == null || value.isEmpty()) {
            return permissions;
        }
        for (final var permission : value.split(";")) {
            if (!permission.isEmpty()) {
                permissions.add(permission);
            }
        }
        return permissions;
    }

    private static void checkSize(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("Session record exceeds " + MAX_RECORD_BYTES + " bytes");
        }
    }
}
