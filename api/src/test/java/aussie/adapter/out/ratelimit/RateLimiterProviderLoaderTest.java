package aussie.adapter.out.ratelimit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.out.ratelimit.memory.InMemoryRateLimiter;
import aussie.core.config.RateLimitingConfig;
import aussie.core.model.ratelimit.AlgorithmRegistry;
import aussie.core.model.ratelimit.RateLimitAlgorithm;
import aussie.core.port.out.RateLimiter;

@DisplayName("RateLimiterProviderLoader")
@ExtendWith(MockitoExtension.class)
class RateLimiterProviderLoaderTest {

    @Mock
    private RateLimitingConfig config;

    @Mock
    private AlgorithmRegistry algorithmRegistry;

    @Mock
    private Instance<ReactiveRedisDataSource> redisDataSource;

    @Mock
    private RateLimitingConfig.RedisConfig redisConfig;

    private RateLimiter producedLimiter;

    @AfterEach
    void tearDown() {
        if (producedLimiter instanceof InMemoryRateLimiter inMemory) {
            inMemory.shutdown();
        }
    }

    private RateLimiterProviderLoader createLoader() {
        return new RateLimiterProviderLoader(config, algorithmRegistry, redisDataSource);
    }

    @Nested
    @DisplayName("produceRateLimiter()")
    class ProduceRateLimiterTests {

        @Test
        @DisplayName("should return NoOpRateLimiter when rate limiting is disabled")
        void shouldReturnNoOpWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            final var loader = createLoader();
            producedLimiter = loader.produceRateLimiter();

            assertInstanceOf(NoOpRateLimiter.class, producedLimiter);
        }

        @Test
        @DisplayName("should return in-memory limiter when Redis is not enabled")
        void shouldReturnInMemoryWhenRedisDisabled() {
            when(config.enabled()).thenReturn(true);
            when(config.redis()).thenReturn(redisConfig);
            when(redisConfig.enabled()).thenReturn(false);
            when(config.algorithm()).thenReturn(RateLimitAlgorithm.BUCKET);
            when(config.defaultRequestsPerWindow()).thenReturn(100L);
            when(config.windowSeconds()).thenReturn(60L);

            final var loader = createLoader();
            producedLimiter = loader.produceRateLimiter();

            assertInstanceOf(InMemoryRateLimiter.class, producedLimiter);
        }

        @Test
        @DisplayName("should fall back to in-memory when Redis is enabled but not resolvable")
        void shouldFallbackWhenRedisNotResolvable() {
            when(config.enabled()).thenReturn(true);
            when(config.redis()).thenReturn(redisConfig);
            when(redisConfig.enabled()).thenReturn(true);
            when(redisDataSource.isResolvable()).thenReturn(false);
            when(config.algorithm()).thenReturn(RateLimitAlgorithm.BUCKET);
            when(config.defaultRequestsPerWindow()).thenReturn(100L);
            when(config.windowSeconds()).thenReturn(60L);

            final var loader = createLoader();
            producedLimiter = loader.produceRateLimiter();

            assertInstanceOf(InMemoryRateLimiter.class, producedLimiter);
        }
    }

    @Nested
    @DisplayName("disposeRateLimiter()")
    class DisposeRateLimiterTests {

        @Test
        @DisplayName("should call shutdown on InMemoryRateLimiter")
        void shouldShutdownInMemoryRateLimiter() {
            final var inMemoryLimiter = mock(InMemoryRateLimiter.class);

            final var loader = createLoader();
            loader.disposeRateLimiter(inMemoryLimiter);

            verify(inMemoryLimiter).shutdown();
        }

        @Test
        @DisplayName("should not throw for NoOpRateLimiter")
        void shouldNotThrowForNoOp() {
            final var noOpLimiter = NoOpRateLimiter.getInstance();

            final var loader = createLoader();
            // Should complete without exception
            loader.disposeRateLimiter(noOpLimiter);
        }
    }
}
