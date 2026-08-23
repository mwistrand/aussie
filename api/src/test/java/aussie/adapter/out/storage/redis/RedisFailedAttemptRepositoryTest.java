package aussie.adapter.out.storage.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.hash.ReactiveHashCommands;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.ResiliencyConfig;
import aussie.core.port.out.Metrics;

@ExtendWith(MockitoExtension.class)
class RedisFailedAttemptRepositoryTest {

    private static final String KEY = "ip:192.0.2.1";

    @Mock
    private ReactiveRedisDataSource dataSource;

    @Mock
    private ReactiveValueCommands<String, String> valueCommands;

    @Mock
    private ReactiveHashCommands<String, String, String> hashCommands;

    @Mock
    private ReactiveKeyCommands<String> keyCommands;

    @Mock
    private ResiliencyConfig resiliencyConfig;

    @Mock
    private ResiliencyConfig.RedisConfig redisConfig;

    @Mock
    private Metrics metrics;

    private RedisFailedAttemptRepository repository;

    @BeforeEach
    void setUp() {
        when(dataSource.value(String.class, String.class)).thenReturn(valueCommands);
        when(dataSource.hash(String.class, String.class, String.class)).thenReturn(hashCommands);
        when(dataSource.key(String.class)).thenReturn(keyCommands);
        when(resiliencyConfig.redis()).thenReturn(redisConfig);
        when(redisConfig.operationTimeout()).thenReturn(Duration.ofSeconds(1));
        repository = new RedisFailedAttemptRepository(dataSource, resiliencyConfig, metrics);
    }

    @Test
    void readsLegacyFailedAttemptCountDuringKeyMigration() {
        when(valueCommands.get(currentKey("aussie:auth:failed:")))
                .thenReturn(Uni.createFrom().nullItem());
        when(valueCommands.get("aussie:auth:failed:" + KEY))
                .thenReturn(Uni.createFrom().item("4"));

        assertEquals(4, repository.getFailedAttemptCount(KEY).await().indefinitely());
    }

    @Test
    void recognizesLegacyLockoutDuringKeyMigration() {
        when(keyCommands.exists(currentKey("aussie:auth:lockout:")))
                .thenReturn(Uni.createFrom().item(false));
        when(keyCommands.exists("aussie:auth:lockout:" + KEY))
                .thenReturn(Uni.createFrom().item(true));

        assertTrue(repository.isLockedOut(KEY).await().indefinitely());
    }

    @Test
    void derivesCurrentLockoutExpiryFromRedisTtl() {
        when(keyCommands.ttl(currentKey("aussie:auth:lockout:")))
                .thenReturn(Uni.createFrom().item(120L));

        final var before = Instant.now().plusSeconds(119);
        final var expiry = repository.getLockoutExpiry(KEY).await().indefinitely();
        final var after = Instant.now().plusSeconds(121);

        assertTrue(!expiry.isBefore(before));
        assertTrue(!expiry.isAfter(after));
    }

    @Test
    void readsLegacyLockoutExpiryDuringKeyMigration() {
        final var expected = Instant.parse("2026-08-23T12:00:00Z");
        when(keyCommands.ttl(currentKey("aussie:auth:lockout:")))
                .thenReturn(Uni.createFrom().item(-2L));
        when(hashCommands.hget(currentKey("aussie:auth:lockout:"), "expiresAt"))
                .thenReturn(Uni.createFrom().nullItem());
        when(hashCommands.hget("aussie:auth:lockout:" + KEY, "expiresAt"))
                .thenReturn(Uni.createFrom().item(String.valueOf(expected.toEpochMilli())));

        assertEquals(expected, repository.getLockoutExpiry(KEY).await().indefinitely());
    }

    @Test
    void clearsCurrentAndLegacyLockoutsSeparatelyForRedisCluster() {
        when(keyCommands.del(currentKey("aussie:auth:lockout:")))
                .thenReturn(Uni.createFrom().item(1));
        when(keyCommands.del("aussie:auth:lockout:" + KEY))
                .thenReturn(Uni.createFrom().item(1));

        repository.clearLockout(KEY).await().indefinitely();

        verify(keyCommands).del(currentKey("aussie:auth:lockout:"));
        verify(keyCommands).del("aussie:auth:lockout:" + KEY);
    }

    private String currentKey(String prefix) {
        final var encoded =
                Base64.getUrlEncoder().withoutPadding().encodeToString(KEY.getBytes(StandardCharsets.UTF_8));
        return prefix + "{" + encoded + "}";
    }
}
