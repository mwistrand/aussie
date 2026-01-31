package aussie.adapter.out.storage.redis;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.SetArgs;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.SessionConfig;
import aussie.core.model.session.Session;
import aussie.core.port.out.SessionRepository;

/**
 * Redis implementation of SessionRepository.
 *
 * <p>Sessions are stored as serialized JSON with Redis TTL for automatic expiration.
 * Uses SETNX semantics for atomic insert-if-absent to prevent ID collisions.
 */
public class RedisSessionRepository implements SessionRepository {

    private static final Logger LOG = Logger.getLogger(RedisSessionRepository.class);
    private static final String USER_INDEX_PREFIX = "aussie:session:user:";

    private final ReactiveValueCommands<String, String> valueCommands;
    private final ReactiveKeyCommands<String> keyCommands;
    private final String keyPrefix;
    private final Duration sessionTtl;
    private final RedisTimeoutHelper timeoutHelper;

    public RedisSessionRepository(
            ReactiveRedisDataSource redisDataSource, SessionConfig config, RedisTimeoutHelper timeoutHelper) {
        this.valueCommands = redisDataSource.value(String.class, String.class);
        this.keyCommands = redisDataSource.key(String.class);
        this.keyPrefix = config.storage().redis().keyPrefix();
        this.sessionTtl = config.ttl();
        this.timeoutHelper = timeoutHelper;
    }

    @Override
    public Uni<Boolean> saveIfAbsent(Session session) {
        String key = keyPrefix + session.id();
        String value = serialize(session);

        // Use SETNX semantics (SET with NX flag)
        SetArgs args = new SetArgs().nx().ex(sessionTtl);

        var operation = valueCommands.set(key, value, args).map(result -> {
            if (result != null) {
                // Successfully set - update user index
                updateUserIndex(session.userId(), session.id());
                LOG.debugf("Session created in Redis: %s", session.id());
                return true;
            }
            LOG.debugf("Session ID collision in Redis: %s", session.id());
            return false;
        });
        return timeoutHelper.withTimeout(operation, "saveIfAbsent");
    }

    @Override
    public Uni<Session> save(Session session) {
        String key = keyPrefix + session.id();
        String value = serialize(session);

        var operation = valueCommands
                .setex(key, sessionTtl.toSeconds(), value)
                .replaceWith(session)
                .invoke(() -> updateUserIndex(session.userId(), session.id()));
        return timeoutHelper.withTimeout(operation, "save");
    }

    @Override
    public Uni<Optional<Session>> findById(String sessionId) {
        String key = keyPrefix + sessionId;

        var operation = valueCommands.get(key).map(value -> {
            if (value == null) {
                return Optional.<Session>empty();
            }
            return Optional.of(deserialize(value));
        });
        return timeoutHelper.withTimeout(operation, "findById");
    }

    @Override
    public Uni<Session> update(Session session) {
        String key = keyPrefix + session.id();
        String value = serialize(session);

        // Keep existing TTL by using SETEX with remaining TTL
        var operation = keyCommands
                .ttl(key)
                .flatMap(ttl -> {
                    long seconds = ttl > 0 ? ttl : sessionTtl.toSeconds();
                    return valueCommands.setex(key, seconds, value);
                })
                .replaceWith(session);
        return timeoutHelper.withTimeout(operation, "update");
    }

    @Override
    public Uni<Void> delete(String sessionId) {
        String key = keyPrefix + sessionId;

        var operation = findById(sessionId)
                .flatMap(sessionOpt -> {
                    if (sessionOpt.isPresent()) {
                        removeFromUserIndex(sessionOpt.get().userId(), sessionId);
                    }
                    return keyCommands.del(key);
                })
                .replaceWithVoid();
        return timeoutHelper.withTimeout(operation, "delete");
    }

    @Override
    public Uni<Void> deleteByUserId(String userId) {
        String userIndexKey = USER_INDEX_PREFIX + userId;

        var operation = valueCommands.get(userIndexKey).flatMap(sessionIds -> {
            if (sessionIds == null || sessionIds.isBlank()) {
                return Uni.createFrom().voidItem();
            }

            String[] ids = sessionIds.split(",");
            var deletions = new java.util.ArrayList<Uni<Integer>>();

            for (String sessionId : ids) {
                if (!sessionId.isBlank()) {
                    deletions.add(keyCommands.del(keyPrefix + sessionId.trim()));
                }
            }

            return Uni.join()
                    .all(deletions)
                    .andCollectFailures()
                    .flatMap(results -> keyCommands.del(userIndexKey))
                    .replaceWithVoid();
        });
        return timeoutHelper.withTimeout(operation, "deleteByUserId");
    }

    @Override
    public Uni<Boolean> exists(String sessionId) {
        String key = keyPrefix + sessionId;
        // exists(String key) returns Uni<Boolean> directly
        var operation = keyCommands.exists(key);
        return timeoutHelper.withTimeout(operation, "exists");
    }

    private void updateUserIndex(String userId, String sessionId) {
        String userIndexKey = USER_INDEX_PREFIX + userId;

        valueCommands
                .get(userIndexKey)
                .subscribe()
                .with(
                        existing -> {
                            String newValue;
                            if (existing == null || existing.isBlank()) {
                                newValue = sessionId;
                            } else if (!existing.contains(sessionId)) {
                                newValue = existing + "," + sessionId;
                            } else {
                                return; // Already indexed
                            }
                            valueCommands
                                    .setex(userIndexKey, sessionTtl.toSeconds() * 2, newValue)
                                    .subscribe()
                                    .with(
                                            success -> {},
                                            error -> LOG.warnf("Failed to update user index: %s", error.getMessage()));
                        },
                        error -> LOG.warnf("Failed to read user index: %s", error.getMessage()));
    }

    private void removeFromUserIndex(String userId, String sessionId) {
        String userIndexKey = USER_INDEX_PREFIX + userId;

        valueCommands
                .get(userIndexKey)
                .subscribe()
                .with(
                        existing -> {
                            if (existing == null || existing.isBlank()) {
                                return;
                            }
                            String[] ids = existing.split(",");
                            StringBuilder newValue = new StringBuilder();
                            for (String id : ids) {
                                if (!id.trim().equals(sessionId)) {
                                    if (newValue.length() > 0) {
                                        newValue.append(",");
                                    }
                                    newValue.append(id.trim());
                                }
                            }
                            if (newValue.length() > 0) {
                                valueCommands
                                        .setex(userIndexKey, sessionTtl.toSeconds() * 2, newValue.toString())
                                        .subscribe()
                                        .with(success -> {}, error -> {});
                            } else {
                                keyCommands.del(userIndexKey).subscribe().with(success -> {}, error -> {});
                            }
                        },
                        error -> LOG.warnf("Failed to update user index: %s", error.getMessage()));
    }

    private String serialize(Session session) {
        // Simple pipe-separated format for efficiency
        StringBuilder sb = new StringBuilder();
        sb.append(session.id()).append("|");
        sb.append(session.userId()).append("|");
        sb.append(session.issuer() != null ? session.issuer() : "").append("|");
        sb.append(serializeClaims(session.claims())).append("|");
        sb.append(serializePermissions(session.permissions())).append("|");
        sb.append(session.createdAt().toEpochMilli()).append("|");
        sb.append(session.expiresAt() != null ? session.expiresAt().toEpochMilli() : "")
                .append("|");
        sb.append(session.lastAccessedAt() != null ? session.lastAccessedAt().toEpochMilli() : "")
                .append("|");
        sb.append(session.userAgent() != null ? session.userAgent() : "").append("|");
        sb.append(session.ipAddress() != null ? session.ipAddress() : "");
        return sb.toString();
    }

    private Session deserialize(String value) {
        String[] parts = value.split("\\|", -1);
        if (parts.length < 10) {
            throw new IllegalArgumentException("Invalid session format");
        }

        return new Session(
                parts[0],
                parts[1],
                parts[2].isEmpty() ? null : parts[2],
                deserializeClaims(parts[3]),
                deserializePermissions(parts[4]),
                Instant.ofEpochMilli(Long.parseLong(parts[5])),
                parts[6].isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(parts[6])),
                parts[7].isEmpty() ? null : Instant.ofEpochMilli(Long.parseLong(parts[7])),
                parts[8].isEmpty() ? null : parts[8],
                parts[9].isEmpty() ? null : parts[9]);
    }

    private String serializeClaims(Map<String, Object> claims) {
        if (claims == null || claims.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var entry : claims.entrySet()) {
            if (sb.length() > 0) {
                sb.append(";");
            }
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private Map<String, Object> deserializeClaims(String value) {
        Map<String, Object> claims = new HashMap<>();
        if (value == null || value.isEmpty()) {
            return claims;
        }
        for (String pair : value.split(";")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                claims.put(kv[0], kv[1]);
            }
        }
        return claims;
    }

    private String serializePermissions(Set<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return "";
        }
        return String.join(";", permissions);
    }

    private Set<String> deserializePermissions(String value) {
        Set<String> permissions = new HashSet<>();
        if (value == null || value.isEmpty()) {
            return permissions;
        }
        for (String perm : value.split(";")) {
            if (!perm.isEmpty()) {
                permissions.add(perm);
            }
        }
        return permissions;
    }
}
