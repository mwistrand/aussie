package aussie.adapter.out.storage.redis;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.ApiKey;
import aussie.spi.StorageAdapterConfig;

@SuppressWarnings("unchecked")
class RedisAuthKeyCacheProviderTest {

    @Test
    void usesConfiguredAuthCacheTtlForCacheWrites() {
        final var dataSource = mock(ReactiveRedisDataSource.class);
        final var valueCommands = mock(ReactiveValueCommands.class);
        final var keyCommands = mock(ReactiveKeyCommands.class);
        final var config = mock(StorageAdapterConfig.class);
        when(dataSource.value(String.class, String.class)).thenReturn(valueCommands);
        when(dataSource.key(String.class)).thenReturn(keyCommands);
        when(config.getDuration("aussie.auth.cache.ttl")).thenReturn(Optional.of(Duration.ofMinutes(14)));
        when(valueCommands.setex(eq("aussie:authkey:key-hash"), eq(840L), anyString()))
                .thenReturn(Uni.createFrom().voidItem());

        final var provider = new RedisAuthKeyCacheProvider();
        provider.setDataSource(dataSource);
        final var cache = provider.createCache(config);

        cache.put("key-hash", ApiKey.builder("key-id", "key-hash").build())
                .await()
                .indefinitely();

        verify(valueCommands).setex(eq("aussie:authkey:key-hash"), eq(840L), anyString());
    }
}
