package aussie.adapter.out.storage.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
class RedisPkceChallengeRepositoryIntegrationTest {

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
    void oneTimeConsumptionIsAtomicAcrossIndependentClients() throws Exception {
        final var key = "aussie:pkce:cross-instance";
        firstClient
                .send(Request.cmd(Command.SET).arg(key).arg("transaction"))
                .await()
                .atMost(Duration.ofSeconds(5));
        final var executor = Executors.newFixedThreadPool(2);

        try {
            final Future<String> first = executor.submit(() -> getAndDelete(firstClient, key));
            final Future<String> second = executor.submit(() -> getAndDelete(secondClient, key));

            final var firstResult = first.get();
            final var secondResult = second.get();
            assertTrue((firstResult == null) ^ (secondResult == null));
            assertEquals("transaction", firstResult == null ? secondResult : firstResult);
        } finally {
            executor.shutdownNow();
        }
    }

    private String getAndDelete(Redis client, String key) {
        final var response =
                client.send(Request.cmd(Command.GETDEL).arg(key)).await().atMost(Duration.ofSeconds(5));
        return response == null ? null : response.toString();
    }
}
