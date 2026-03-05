package aussie.adapter.out.storage;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.ApiKey;

@DisplayName("NoOpAuthKeyCache")
class NoOpAuthKeyCacheTest {

    private final NoOpAuthKeyCache cache = NoOpAuthKeyCache.INSTANCE;

    @Nested
    @DisplayName("INSTANCE")
    class InstanceTests {

        @Test
        @DisplayName("should provide a non-null singleton instance")
        void shouldProvideNonNullSingleton() {
            assertNotNull(NoOpAuthKeyCache.INSTANCE);
        }
    }

    @Nested
    @DisplayName("get()")
    class GetTests {

        @Test
        @DisplayName("should return empty Optional")
        void shouldReturnEmpty() {
            final var result = cache.get("some-key-hash").await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty even after put")
        void shouldReturnEmptyAfterPut() {
            final var apiKey = ApiKey.builder("key1", "hash1")
                    .name("test-key")
                    .createdBy("test")
                    .createdAt(Instant.now())
                    .build();

            cache.put("hash1", apiKey).await().atMost(Duration.ofSeconds(5));

            final var result = cache.get("hash1").await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("put()")
    class PutTests {

        @Test
        @DisplayName("should complete successfully")
        void shouldCompleteSuccessfully() {
            final var apiKey = ApiKey.builder("key1", "hash1")
                    .name("test-key")
                    .createdBy("test")
                    .createdAt(Instant.now())
                    .build();

            final var result = cache.put("hash1", apiKey).await().atMost(Duration.ofSeconds(5));
            assertNull(result);
        }
    }

    @Nested
    @DisplayName("invalidate()")
    class InvalidateTests {

        @Test
        @DisplayName("should complete successfully")
        void shouldCompleteSuccessfully() {
            final var result = cache.invalidate("some-key-hash").await().atMost(Duration.ofSeconds(5));

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
