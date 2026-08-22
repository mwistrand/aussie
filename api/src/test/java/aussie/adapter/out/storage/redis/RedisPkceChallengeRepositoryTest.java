package aussie.adapter.out.storage.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.PkceConfig;
import aussie.core.model.auth.OidcAuthorizationTransaction;

@ExtendWith(MockitoExtension.class)
class RedisPkceChallengeRepositoryTest {

    @Mock
    private ReactiveRedisDataSource dataSource;

    @Mock
    private ReactiveValueCommands<String, String> commands;

    @Mock
    private PkceConfig config;

    @Mock
    private PkceConfig.StorageConfig storageConfig;

    @Mock
    private PkceConfig.StorageConfig.RedisConfig redisConfig;

    private RedisPkceChallengeRepository repository;

    @BeforeEach
    void setUp() {
        when(dataSource.value(String.class, String.class)).thenReturn(commands);
        when(config.storage()).thenReturn(storageConfig);
        when(storageConfig.redis()).thenReturn(redisConfig);
        when(redisConfig.keyPrefix()).thenReturn("test:oidc:");
        repository = new RedisPkceChallengeRepository(dataSource, config);
    }

    @Test
    void storesCompleteTransactionWithTtl() {
        final var transaction = transaction();
        when(commands.setex(eq("test:oidc:state"), eq(600L), anyString()))
                .thenReturn(Uni.createFrom().voidItem());

        repository.store("state", transaction, Duration.ofMinutes(10)).await().indefinitely();

        final var value = ArgumentCaptor.forClass(String.class);
        verify(commands).setex(eq("test:oidc:state"), eq(600L), value.capture());
        final var json = new JsonObject(value.getValue());
        assertEquals(transaction.providerId(), json.getString("providerId"));
        assertEquals(transaction.redirectUri(), json.getString("redirectUri"));
        assertEquals(transaction.codeChallenge(), json.getString("codeChallenge"));
        assertEquals(transaction.nonce(), json.getString("nonce"));
        assertEquals(transaction.clientType().name(), json.getString("clientType"));
        assertEquals(transaction.createdAt().getEpochSecond(), json.getLong("createdAt"));
        assertEquals(transaction.expiresAt().getEpochSecond(), json.getLong("expiresAt"));
    }

    @Test
    void atomicallyConsumesCompleteTransaction() {
        final var transaction = transaction();
        final var value = new JsonObject()
                .put("providerId", transaction.providerId())
                .put("redirectUri", transaction.redirectUri())
                .put("codeChallenge", transaction.codeChallenge())
                .put("nonce", transaction.nonce())
                .put("clientType", transaction.clientType().name())
                .put("createdAt", transaction.createdAt().getEpochSecond())
                .put("expiresAt", transaction.expiresAt().getEpochSecond())
                .encode();
        when(commands.getdel("test:oidc:state")).thenReturn(Uni.createFrom().item(value));

        final var result = repository.consume("state").await().indefinitely();

        assertEquals(transaction, result.orElseThrow());
        verify(commands).getdel("test:oidc:state");
    }

    private OidcAuthorizationTransaction transaction() {
        final var createdAt = Instant.ofEpochSecond(1_700_000_000L);
        return new OidcAuthorizationTransaction(
                "provider",
                "https://app.example.com/callback",
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                "nonce",
                OidcAuthorizationTransaction.ClientType.SESSION,
                createdAt,
                createdAt.plusSeconds(600));
    }
}
