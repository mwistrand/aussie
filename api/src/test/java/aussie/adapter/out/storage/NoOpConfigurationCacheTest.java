package aussie.adapter.out.storage;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.service.ServiceRegistration;

@DisplayName("NoOpConfigurationCache")
class NoOpConfigurationCacheTest {

    private final NoOpConfigurationCache cache = NoOpConfigurationCache.INSTANCE;

    @Nested
    @DisplayName("INSTANCE")
    class InstanceTests {

        @Test
        @DisplayName("should provide a non-null singleton instance")
        void shouldProvideNonNullSingleton() {
            assertNotNull(NoOpConfigurationCache.INSTANCE);
        }
    }

    @Nested
    @DisplayName("get()")
    class GetTests {

        @Test
        @DisplayName("should return empty Optional")
        void shouldReturnEmpty() {
            final var result = cache.get("service-1").await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("put(ServiceRegistration)")
    class PutTests {

        @Test
        @DisplayName("should complete successfully")
        void shouldCompleteSuccessfully() {
            final var service = mock(ServiceRegistration.class);

            final var result = cache.put(service).await().atMost(Duration.ofSeconds(5));

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("put(ServiceRegistration, Duration)")
    class PutWithTtlTests {

        @Test
        @DisplayName("should complete successfully with TTL")
        void shouldCompleteSuccessfullyWithTtl() {
            final var service = mock(ServiceRegistration.class);
            final var ttl = Duration.ofMinutes(10);

            final var result = cache.put(service, ttl).await().atMost(Duration.ofSeconds(5));

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("invalidate()")
    class InvalidateTests {

        @Test
        @DisplayName("should complete successfully")
        void shouldCompleteSuccessfully() {
            final var result = cache.invalidate("service-1").await().atMost(Duration.ofSeconds(5));

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("invalidateAll()")
    class InvalidateAllTests {

        @Test
        @DisplayName("should complete successfully")
        void shouldCompleteSuccessfully() {
            final var result = cache.invalidateAll().await().atMost(Duration.ofSeconds(5));

            assertNull(result);
        }
    }
}
