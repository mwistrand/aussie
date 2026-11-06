package aussie.adapter.out.storage.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.health.HealthCheckResponse.Status;
import org.junit.jupiter.api.Test;

import aussie.core.config.ResiliencyConfig;
import aussie.core.config.SessionConfig;
import aussie.core.port.out.Metrics;

@SuppressWarnings("unchecked")
class RedisSessionStorageProviderTest {

    @Test
    void availabilityRecoversAfterRedisFailure() {
        final var redis = mock(ReactiveRedisDataSource.class);
        final var keys = mock(ReactiveKeyCommands.class);
        when(redis.key(String.class)).thenReturn(keys);
        when(keys.exists("test-connection"))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("down")))
                .thenReturn(Uni.createFrom().item(true));
        final var sessionConfig = mock(SessionConfig.class);
        final var storageConfig = mock(SessionConfig.StorageConfig.class);
        final var redisConfig = mock(SessionConfig.StorageConfig.RedisConfig.class);
        when(sessionConfig.storage()).thenReturn(storageConfig);
        when(storageConfig.redis()).thenReturn(redisConfig);
        when(redisConfig.keyPrefix()).thenReturn("aussie:session:");

        final var provider = new RedisSessionStorageProvider(
                redis,
                sessionConfig,
                mock(ResiliencyConfig.class),
                mock(Metrics.class),
                mock(ObjectMapper.class),
                "standalone");

        provider.refreshAvailability();
        assertFalse(provider.isAvailable());
        assertTrue(provider.healthCheck().isPresent());
        assertEquals(Status.DOWN, provider.healthCheck().orElseThrow().getStatus());

        provider.refreshAvailability();
        assertTrue(provider.isAvailable());
        assertEquals(Status.UP, provider.healthCheck().orElseThrow().getStatus());
    }

    @Test
    void doesNotStartAnotherProbeUntilTheCurrentProbeCompletes() {
        final var redis = mock(ReactiveRedisDataSource.class);
        final var keys = mock(ReactiveKeyCommands.class);
        when(redis.key(String.class)).thenReturn(keys);
        final var complete = new AtomicReference<Runnable>();
        final Uni<Boolean> pending = Uni.createFrom().emitter(emitter -> complete.set(() -> emitter.complete(true)));
        when(keys.exists("test-connection"))
                .thenReturn(pending)
                .thenReturn(Uni.createFrom().item(true));
        final var provider = new RedisSessionStorageProvider(
                redis,
                mock(SessionConfig.class),
                mock(ResiliencyConfig.class),
                mock(Metrics.class),
                mock(ObjectMapper.class),
                "standalone");

        provider.refreshAvailability();
        provider.refreshAvailability();
        verify(keys, times(1)).exists("test-connection");

        complete.get().run();
        provider.refreshAvailability();
        verify(keys, times(2)).exists("test-connection");
    }

    @Test
    void rejectsRedisCluster() {
        final var provider = new RedisSessionStorageProvider(
                mock(ReactiveRedisDataSource.class),
                mock(SessionConfig.class),
                mock(ResiliencyConfig.class),
                mock(Metrics.class),
                mock(ObjectMapper.class),
                "cluster");

        provider.refreshAvailability();

        assertFalse(provider.isAvailable());
        assertEquals(Status.DOWN, provider.healthCheck().orElseThrow().getStatus());
        assertThrows(IllegalStateException.class, provider::createRepository);
    }
}
