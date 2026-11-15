package aussie.adapter.out.storage.redis;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import aussie.core.model.service.ServiceRegistration;
import aussie.spi.StorageAdapterConfig;

@SuppressWarnings("unchecked")
class RedisCacheProviderTest {

    @Test
    void usesConfiguredStorageCacheTtlForCacheWrites() {
        final var dataSource = mock(ReactiveRedisDataSource.class);
        final var valueCommands = mock(ReactiveValueCommands.class);
        final var keyCommands = mock(ReactiveKeyCommands.class);
        final var config = mock(StorageAdapterConfig.class);
        final CDI<Object> cdi = mock(CDI.class);
        final Instance<ReactiveRedisDataSource> dataSourceInstance = mock(Instance.class);
        when(dataSource.value(String.class, String.class)).thenReturn(valueCommands);
        when(dataSource.key(String.class)).thenReturn(keyCommands);
        when(config.getDuration("aussie.storage.cache.ttl")).thenReturn(Optional.of(Duration.ofMinutes(13)));
        when(dataSourceInstance.get()).thenReturn(dataSource);
        when(cdi.select(ReactiveRedisDataSource.class)).thenReturn(dataSourceInstance);
        when(valueCommands.setex(eq("aussie:config:service-id"), eq(780L), anyString()))
                .thenReturn(Uni.createFrom().voidItem());

        try (final var current = mockStatic(CDI.class)) {
            current.when(CDI::current).thenReturn(cdi);

            final var cache = new RedisCacheProvider().createCache(config);
            cache.put(ServiceRegistration.builder("service-id")
                            .baseUrl("http://backend")
                            .build())
                    .await()
                    .indefinitely();
        }

        verify(valueCommands).setex(eq("aussie:config:service-id"), eq(780L), anyString());
    }
}
