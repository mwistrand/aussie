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

import aussie.core.model.auth.TranslationConfigSchema;
import aussie.core.model.auth.TranslationConfigVersion;
import aussie.spi.StorageAdapterConfig;

@SuppressWarnings("unchecked")
class RedisTranslationConfigCacheProviderTest {

    @Test
    void usesConfiguredTranslationCacheTtlForCacheWrites() {
        final var dataSource = mock(ReactiveRedisDataSource.class);
        final var valueCommands = mock(ReactiveValueCommands.class);
        final var keyCommands = mock(ReactiveKeyCommands.class);
        final var config = mock(StorageAdapterConfig.class);
        when(dataSource.value(String.class, String.class)).thenReturn(valueCommands);
        when(dataSource.key(String.class)).thenReturn(keyCommands);
        when(config.getDuration("aussie.translation-config.cache.redis.ttl"))
                .thenReturn(Optional.of(Duration.ofMinutes(15)));
        when(valueCommands.setex(eq("aussie:translation-config:version:version-id"), eq(900L), anyString()))
                .thenReturn(Uni.createFrom().voidItem());

        final var provider = new RedisTranslationConfigCacheProvider();
        provider.setDataSource(dataSource);
        final var cache = provider.createCache(config);

        cache.put(TranslationConfigVersion.create("version-id", 1, TranslationConfigSchema.empty(), "test", null))
                .await()
                .indefinitely();

        verify(valueCommands).setex(eq("aussie:translation-config:version:version-id"), eq(900L), anyString());
    }
}
