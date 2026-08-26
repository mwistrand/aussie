package aussie.adapter.out.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.out.ratelimit.memory.InMemoryRateLimiter;
import aussie.adapter.out.ratelimit.redis.RedisRateLimiter;
import aussie.core.config.RateLimitingConfig;
import aussie.core.config.RateLimitingConfig.RateLimitFallbackBehavior;
import aussie.core.model.ratelimit.RateLimitAlgorithm;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.RateLimiter;
import aussie.core.service.ratelimit.AlgorithmRegistry;

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

    @Mock
    private RateLimitingConfig.FallbackConfig fallbackConfig;

    @Mock
    private Instance<Metrics> metricsInstance;

    private RateLimiter producedLimiter;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient()
                .when(algorithmRegistry.isAvailable(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        if (producedLimiter != null) {
            producedLimiter.close();
        }
    }

    private RateLimiterProviderLoader createLoader() {
        return new RateLimiterProviderLoader(config, algorithmRegistry, redisDataSource, metricsInstance);
    }

    private void stubFallback(RateLimitFallbackBehavior behavior) {
        when(config.fallback()).thenReturn(fallbackConfig);
        when(fallbackConfig.behavior()).thenReturn(behavior);
        when(metricsInstance.isResolvable()).thenReturn(false);
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
        @DisplayName("should fail startup when Redis is enabled but not resolvable")
        void shouldFailWhenRedisNotResolvable() {
            when(config.enabled()).thenReturn(true);
            when(config.redis()).thenReturn(redisConfig);
            when(redisConfig.enabled()).thenReturn(true);
            when(redisDataSource.isResolvable()).thenReturn(false);
            when(config.algorithm()).thenReturn(RateLimitAlgorithm.BUCKET);

            final var loader = createLoader();
            assertThrows(IllegalStateException.class, loader::produceRateLimiter);
        }

        @Test
        @DisplayName("should use Redis limiter when Redis is enabled, resolvable, and available")
        void shouldUseRedisWhenAvailable() {
            when(config.enabled()).thenReturn(true);
            when(config.redis()).thenReturn(redisConfig);
            when(redisConfig.enabled()).thenReturn(true);
            when(redisDataSource.isResolvable()).thenReturn(true);
            when(redisDataSource.get()).thenReturn(mock(ReactiveRedisDataSource.class));
            when(config.algorithm()).thenReturn(RateLimitAlgorithm.BUCKET);
            when(config.defaultRequestsPerWindow()).thenReturn(100L);
            when(config.windowSeconds()).thenReturn(60L);
            stubFallback(RateLimitFallbackBehavior.LOCAL_BUCKET);

            final var loader = createLoader();
            producedLimiter = loader.produceRateLimiter();

            assertInstanceOf(RedisRateLimiter.class, producedLimiter);
        }

        @Test
        @DisplayName("should fail startup when Redis get() throws")
        void shouldFailWhenRedisGetThrows() {
            when(config.enabled()).thenReturn(true);
            when(config.redis()).thenReturn(redisConfig);
            when(redisConfig.enabled()).thenReturn(true);
            when(redisDataSource.isResolvable()).thenReturn(true);
            when(redisDataSource.get()).thenThrow(new RuntimeException("Redis unavailable"));
            when(config.algorithm()).thenReturn(RateLimitAlgorithm.BUCKET);

            final var loader = createLoader();
            final var error = assertThrows(IllegalStateException.class, loader::produceRateLimiter);
            assertEquals("Redis unavailable", error.getCause().getMessage());
        }

        @Test
        @DisplayName("should reject unsupported algorithms at startup")
        void shouldRejectUnsupportedAlgorithm() {
            when(config.enabled()).thenReturn(true);
            when(config.algorithm()).thenReturn(RateLimitAlgorithm.FIXED_WINDOW);
            when(algorithmRegistry.isAvailable(RateLimitAlgorithm.FIXED_WINDOW)).thenReturn(false);

            assertThrows(IllegalStateException.class, () -> createLoader().produceRateLimiter());
        }
    }

    @Nested
    @DisplayName("disposeRateLimiter()")
    class DisposeRateLimiterTests {

        @Test
        @DisplayName("should close the limiter through the port lifecycle")
        void shouldCloseRateLimiterThroughPortLifecycle() {
            final var rateLimiter = mock(RateLimiter.class);

            final var loader = createLoader();
            loader.disposeRateLimiter(rateLimiter);

            verify(rateLimiter).close();
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
