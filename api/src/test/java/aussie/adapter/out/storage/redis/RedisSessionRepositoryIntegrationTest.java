package aussie.adapter.out.storage.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.quarkus.redis.runtime.datasource.ReactiveRedisDataSourceImpl;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.RedisAPI;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.redis.client.RedisOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import aussie.core.config.SessionConfig;
import aussie.core.model.session.Session;
import aussie.core.util.SecureHash;

class RedisSessionRepositoryIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SESSION_TTL = Duration.ofSeconds(30);
    private static final String KEY_PREFIX = "test:session:";
    private static final String USER_INDEX_PREFIX = "aussie:session:user:";
    private static final String REDIS_URL =
            System.getenv().getOrDefault("AUSSIE_TEST_REDIS_URL", "redis://localhost:6379");

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());

    private static Vertx vertx;
    private static Redis firstClient;
    private static Redis secondClient;

    private RedisSessionRepository firstRepository;
    private RedisSessionRepository secondRepository;

    @BeforeAll
    static void setUpClass() {
        vertx = Vertx.vertx();
        final var options = new RedisOptions().setConnectionString(REDIS_URL);
        firstClient = Redis.createClient(vertx, options);
        secondClient = Redis.createClient(vertx, options);
    }

    @AfterAll
    static void tearDownClass() {
        if (firstClient != null) {
            firstClient.close();
        }
        if (secondClient != null) {
            secondClient.close();
        }
        if (vertx != null) {
            vertx.closeAndAwait();
        }
    }

    @BeforeEach
    void setUp() {
        RedisAPI.api(firstClient).flushall(List.of()).await().atMost(TIMEOUT);
        firstRepository = createRepository(firstClient, SESSION_TTL);
        secondRepository = createRepository(secondClient, SESSION_TTL);
    }

    @Test
    void saveIfAbsentIsAtomicAndIndexesOnlyTheWinningSession() throws Exception {
        final var firstSession = session("shared-id", "user-1");
        final var secondSession = session("shared-id", "user-2");
        final var start = new CountDownLatch(1);
        final var executor = Executors.newFixedThreadPool(2);

        try {
            final var first = executor.submit(() -> {
                start.await();
                return firstRepository.saveIfAbsent(firstSession).await().atMost(TIMEOUT);
            });
            final var second = executor.submit(() -> {
                start.await();
                return secondRepository.saveIfAbsent(secondSession).await().atMost(TIMEOUT);
            });
            start.countDown();

            final var firstSaved = first.get();
            final var secondSaved = second.get();
            assertTrue(firstSaved ^ secondSaved);

            final var winner = firstSaved ? firstSession : secondSession;
            final var loser = firstSaved ? secondSession : firstSession;
            assertEquals(
                    winner,
                    firstRepository
                            .findById("shared-id")
                            .await()
                            .atMost(TIMEOUT)
                            .orElseThrow());
            assertEquals(Set.of("shared-id"), members(userIndexKey(winner.userId())));
            assertTrue(members(userIndexKey(loser.userId())).isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSaveAndUserDeletionNeverLeaveAnUnindexedSession() throws Exception {
        final var executor = Executors.newFixedThreadPool(2);
        try {
            for (var attempt = 0; attempt < 25; attempt++) {
                final var session = session("concurrent-" + attempt, "concurrent-user-" + attempt);
                final var start = new CountDownLatch(1);
                final var save = executor.submit(() -> {
                    start.await();
                    return firstRepository.saveIfAbsent(session).await().atMost(TIMEOUT);
                });
                final var delete = executor.submit(() -> {
                    start.await();
                    secondRepository.deleteByUserId(session.userId()).await().atMost(TIMEOUT);
                    return null;
                });

                start.countDown();
                assertTrue(save.get());
                delete.get();

                final var exists = firstRepository.exists(session.id()).await().atMost(TIMEOUT);
                assertEquals(exists, members(userIndexKey(session.userId())).contains(session.id()));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void deleteAndDeleteByUserIdKeepRecordsAndIndexesConsistent() {
        final var first = session("session-1", "user-1");
        final var second = session("session-2", "user-1");
        final var partiallyDeleted = session("session-partial", "user-1");
        final var otherUser = session("session-3", "user-2");
        firstRepository.save(first).await().atMost(TIMEOUT);
        firstRepository.save(second).await().atMost(TIMEOUT);
        firstRepository.save(partiallyDeleted).await().atMost(TIMEOUT);
        firstRepository.save(otherUser).await().atMost(TIMEOUT);
        firstClient
                .send(Request.cmd(Command.DEL).arg(KEY_PREFIX + partiallyDeleted.id()))
                .await()
                .atMost(TIMEOUT);

        firstRepository.delete(first.id()).await().atMost(TIMEOUT);
        firstRepository.delete(first.id()).await().atMost(TIMEOUT);

        assertFalse(firstRepository.exists(first.id()).await().atMost(TIMEOUT));
        assertEquals(Set.of(second.id(), partiallyDeleted.id()), members(userIndexKey(first.userId())));

        secondRepository.deleteByUserId(first.userId()).await().atMost(TIMEOUT);
        secondRepository.deleteByUserId(first.userId()).await().atMost(TIMEOUT);

        assertFalse(firstRepository.exists(second.id()).await().atMost(TIMEOUT));
        assertTrue(members(userIndexKey(first.userId())).isEmpty());
        assertEquals(
                otherUser,
                firstRepository.findById(otherUser.id()).await().atMost(TIMEOUT).orElseThrow());
    }

    @Test
    void saveAppliesTtlToTheRecordAndUserIndex() {
        final var saved = session("session-1", "user-1");

        firstRepository.saveIfAbsent(saved).await().atMost(TIMEOUT);

        final var recordTtl = ttl(KEY_PREFIX + saved.id());
        final var indexTtl = ttl(userIndexKey(saved.userId()));
        assertTrue(recordTtl > 0 && recordTtl <= SESSION_TTL.toSeconds());
        assertTrue(indexTtl > recordTtl && indexTtl <= SESSION_TTL.toSeconds() * 2);
    }

    @Test
    void findByIdMigratesLegacyRecordsAndBuildsTheUserIndex() {
        final var legacy = "legacy-id|legacy-user|issuer|role=admin|read;write|1000|2000|1500|agent|127.0.0.1";
        firstClient
                .send(Request.cmd(Command.SET)
                        .arg(KEY_PREFIX + "legacy-id")
                        .arg(legacy)
                        .arg("EX")
                        .arg("30"))
                .await()
                .atMost(TIMEOUT);

        final var migrated =
                firstRepository.findById("legacy-id").await().atMost(TIMEOUT).orElseThrow();

        assertEquals("legacy-user", migrated.userId());
        assertTrue(value(KEY_PREFIX + "legacy-id").startsWith("{"));
        assertEquals(Set.of("legacy-id"), members(userIndexKey("legacy-user")));
        assertTrue(ttl(KEY_PREFIX + "legacy-id") > 0);
    }

    @Test
    void legacyMigrationDoesNotRecreateADeletedRecord() {
        final var legacy = "legacy-id|legacy-user|issuer|role=admin|read;write|1000|2000|1500|agent|127.0.0.1";
        final var session = RedisSessionRepository.deserialize(MAPPER, legacy);
        final var key = KEY_PREFIX + session.id();

        firstClient
                .send(Request.cmd(Command.SET).arg(key).arg(legacy).arg("EX").arg("30"))
                .await()
                .atMost(TIMEOUT);
        firstClient.send(Request.cmd(Command.DEL).arg(key)).await().atMost(TIMEOUT);
        final var migrated = firstClient
                .send(Request.cmd(Command.EVAL)
                        .arg(RedisSessionRepository.MIGRATE_SCRIPT)
                        .arg("2")
                        .arg(key)
                        .arg(userIndexKey(session.userId()))
                        .arg(legacy)
                        .arg(RedisSessionRepository.serialize(MAPPER, session))
                        .arg(session.id())
                        .arg("30")
                        .arg("60"))
                .await()
                .atMost(TIMEOUT);

        assertEquals(0L, migrated.toLong());
        assertFalse(firstRepository.exists(session.id()).await().atMost(TIMEOUT));
        assertTrue(members(userIndexKey(session.userId())).isEmpty());
    }

    @Test
    void updateDoesNotRecreateADeletedSession() {
        final var session = session("deleted", "user-1");

        firstRepository.update(session).await().atMost(TIMEOUT);

        assertFalse(firstRepository.exists(session.id()).await().atMost(TIMEOUT));
    }

    private RedisSessionRepository createRepository(Redis client, Duration ttl) {
        final var redisConfig = mock(SessionConfig.StorageConfig.RedisConfig.class);
        when(redisConfig.keyPrefix()).thenReturn(KEY_PREFIX);
        final var storageConfig = mock(SessionConfig.StorageConfig.class);
        when(storageConfig.redis()).thenReturn(redisConfig);
        final var sessionConfig = mock(SessionConfig.class);
        when(sessionConfig.storage()).thenReturn(storageConfig);
        when(sessionConfig.ttl()).thenReturn(ttl);
        final var dataSource = new ReactiveRedisDataSourceImpl(vertx, client, RedisAPI.api(client));
        return new RedisSessionRepository(
                dataSource, sessionConfig, new RedisTimeoutHelper(TIMEOUT, null, "SessionRepository"), MAPPER);
    }

    private Session session(String id, String userId) {
        final var now = Instant.now();
        return new Session(
                id,
                userId,
                "issuer",
                Map.of("subject", userId),
                Set.of("read"),
                now,
                now.plus(SESSION_TTL),
                now,
                "agent",
                "127.0.0.1");
    }

    private Set<String> members(String key) {
        final var response =
                firstClient.send(Request.cmd(Command.SMEMBERS).arg(key)).await().atMost(TIMEOUT);
        final var members = new HashSet<String>();
        for (final var member : response) {
            members.add(member.toString());
        }
        return members;
    }

    private long ttl(String key) {
        return firstClient
                .send(Request.cmd(Command.TTL).arg(key))
                .await()
                .atMost(TIMEOUT)
                .toLong();
    }

    private String value(String key) {
        return firstClient
                .send(Request.cmd(Command.GET).arg(key))
                .await()
                .atMost(TIMEOUT)
                .toString();
    }

    private String userIndexKey(String userId) {
        return USER_INDEX_PREFIX + SecureHash.truncatedSha256(userId, 64);
    }
}
