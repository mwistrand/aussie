package aussie.adapter.out.storage.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisFailedAttemptRepositoryIntegrationTest {

    private static final String KEY = "ip:192.0.2.1";

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static Vertx vertx;
    private static Redis firstClient;
    private static Redis secondClient;

    @BeforeAll
    static void setUpClass() {
        vertx = Vertx.vertx();
        final var options =
                new RedisOptions().setConnectionString("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
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
    void clearRedis() {
        RedisAPI.api(firstClient).flushall(List.of()).await().atMost(Duration.ofSeconds(5));
    }

    @Test
    void concurrentFailedAttemptsAcrossClientsAreNotLostAndKeepTtl() throws Exception {
        final var key = currentKey("aussie:auth:failed:");
        final var attempts = 100;
        final var executor = Executors.newFixedThreadPool(8);
        final var futures = new ArrayList<Future<Long>>();

        recordAttempt(firstClient, key, 60, 0);
        try {
            for (var i = 1; i < attempts; i++) {
                final var client = i % 2 == 0 ? firstClient : secondClient;
                futures.add(executor.submit(() -> recordAttempt(client, key, 3600, 0)));
            }

            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(attempts, getLong(firstClient, Command.GET, key));
        final var ttl = getLong(firstClient, Command.TTL, key);
        assertTrue(ttl > 0);
        assertTrue(ttl <= 60);
    }

    @Test
    void concurrentLockoutTransitionsCreateOneLockoutAndClearAttempts() throws Exception {
        final var lockoutKey = currentKey("aussie:auth:lockout:");
        final var countKey = currentKey("aussie:auth:lockout-count:");
        final var failedKey = currentKey("aussie:auth:failed:");
        final var executor = Executors.newFixedThreadPool(8);
        final var futures = new ArrayList<Future<Long>>();

        set(firstClient, failedKey, "5");
        try {
            for (var i = 0; i < 20; i++) {
                final var client = i % 2 == 0 ? firstClient : secondClient;
                futures.add(executor.submit(() -> recordLockout(client, lockoutKey, countKey, failedKey)));
            }

            for (var future : futures) {
                assertEquals(1, future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals("1", get(firstClient, countKey));
        assertEquals(1, getLong(firstClient, Command.EXISTS, lockoutKey));
        assertEquals(0, getLong(firstClient, Command.EXISTS, failedKey));
        assertTrue(getLong(firstClient, Command.TTL, lockoutKey) > 0);
        assertTrue(getLong(firstClient, Command.TTL, countKey) > 0);
    }

    private long recordAttempt(Redis client, String key, long ttlSeconds, long initialCount) {
        return client.send(Request.cmd(Command.EVAL)
                        .arg(RedisFailedAttemptRepository.RECORD_ATTEMPT_SCRIPT)
                        .arg("1")
                        .arg(key)
                        .arg(String.valueOf(ttlSeconds))
                        .arg(String.valueOf(initialCount)))
                .await()
                .atMost(Duration.ofSeconds(5))
                .toLong();
    }

    private long recordLockout(Redis client, String lockoutKey, String countKey, String failedKey) {
        return client.send(Request.cmd(Command.EVAL)
                        .arg(RedisFailedAttemptRepository.RECORD_LOCKOUT_SCRIPT)
                        .arg("3")
                        .arg(lockoutKey)
                        .arg(countKey)
                        .arg(failedKey)
                        .arg("60")
                        .arg("max_failed_attempts")
                        .arg(String.valueOf(Duration.ofDays(30).toSeconds()))
                        .arg("0"))
                .await()
                .atMost(Duration.ofSeconds(5))
                .toLong();
    }

    private void set(Redis client, String key, String value) {
        client.send(Request.cmd(Command.SET).arg(key).arg(value)).await().atMost(Duration.ofSeconds(5));
    }

    private String get(Redis client, String key) {
        return client.send(Request.cmd(Command.GET).arg(key))
                .await()
                .atMost(Duration.ofSeconds(5))
                .toString();
    }

    private long getLong(Redis client, Command command, String key) {
        return client.send(Request.cmd(command).arg(key))
                .await()
                .atMost(Duration.ofSeconds(5))
                .toLong();
    }

    private String currentKey(String prefix) {
        final var encoded =
                Base64.getUrlEncoder().withoutPadding().encodeToString(KEY.getBytes(StandardCharsets.UTF_8));
        return prefix + "{" + encoded + "}";
    }
}
