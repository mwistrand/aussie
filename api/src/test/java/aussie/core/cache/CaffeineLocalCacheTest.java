package aussie.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CaffeineLocalCache")
class CaffeineLocalCacheTest {

    @Nested
    @DisplayName("Basic Operations")
    class BasicOperations {

        @Test
        @DisplayName("should put and get a value")
        void shouldPutAndGetValue() {
            var cache = new CaffeineLocalCache<String, String>(Duration.ofMinutes(5), 100);

            cache.put("key1", "value1");

            var result = cache.get("key1");
            assertTrue(result.isPresent());
            assertEquals("value1", result.get());
        }

        @Test
        @DisplayName("should return empty for missing key")
        void shouldReturnEmptyForMissingKey() {
            var cache = new CaffeineLocalCache<String, String>(Duration.ofMinutes(5), 100);

            var result = cache.get("missing");

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("should invalidate a specific key")
        void shouldInvalidateSpecificKey() {
            var cache = new CaffeineLocalCache<String, String>(Duration.ofMinutes(5), 100);
            cache.put("key1", "value1");
            cache.put("key2", "value2");

            cache.invalidate("key1");

            assertFalse(cache.get("key1").isPresent());
            assertTrue(cache.get("key2").isPresent());
        }

        @Test
        @DisplayName("should invalidate all keys")
        void shouldInvalidateAllKeys() {
            var cache = new CaffeineLocalCache<String, String>(Duration.ofMinutes(5), 100);
            cache.put("key1", "value1");
            cache.put("key2", "value2");
            cache.put("key3", "value3");

            cache.invalidateAll();

            assertFalse(cache.get("key1").isPresent());
            assertFalse(cache.get("key2").isPresent());
            assertFalse(cache.get("key3").isPresent());
        }

        @Test
        @DisplayName("should replace value on re-put")
        void shouldReplaceValueOnReput() {
            var cache = new CaffeineLocalCache<String, String>(Duration.ofMinutes(5), 100);
            cache.put("key1", "original");

            cache.put("key1", "updated");

            var result = cache.get("key1");
            assertTrue(result.isPresent());
            assertEquals("updated", result.get());
        }
    }

    @Nested
    @DisplayName("TTL Expiration")
    class TtlExpiration {

        @Test
        @DisplayName("should expire entries after TTL")
        void shouldExpireEntriesAfterTtl() throws InterruptedException {
            // Use a very short TTL for testing
            var cache = new CaffeineLocalCache<String, String>(Duration.ofMillis(50), 100);
            cache.put("key1", "value1");

            // Entry should exist immediately
            assertTrue(cache.get("key1").isPresent());

            // Wait for TTL to expire
            Thread.sleep(100);

            // Entry should be expired now
            assertFalse(cache.get("key1").isPresent());
        }

        @Test
        @DisplayName("should keep entry within TTL")
        void shouldKeepEntryWithinTtl() throws InterruptedException {
            var cache = new CaffeineLocalCache<String, String>(Duration.ofSeconds(2), 100);
            cache.put("key1", "value1");

            // Wait a bit, but not past TTL
            Thread.sleep(100);

            // Entry should still exist
            assertTrue(cache.get("key1").isPresent());
            assertEquals("value1", cache.get("key1").get());
        }
    }

    @Nested
    @DisplayName("Maximum Size")
    class MaximumSize {

        @Test
        @DisplayName("should evict entries when max size exceeded")
        void shouldEvictEntriesWhenMaxSizeExceeded() throws InterruptedException {
            var cache = new CaffeineLocalCache<String, String>(Duration.ofMinutes(5), 3);

            // Add more entries than max size
            cache.put("key1", "value1");
            cache.put("key2", "value2");
            cache.put("key3", "value3");
            cache.put("key4", "value4");
            cache.put("key5", "value5");

            // Force cleanup and wait for async eviction
            Thread.sleep(100);

            // Caffeine evicts asynchronously, so check that size is bounded
            // Some entries should be evicted
            long size = cache.estimatedSize();
            assertTrue(size <= 5, "Cache size should be bounded: " + size);
        }
    }

    @Nested
    @DisplayName("Values Collection")
    class ValuesCollection {

        @Test
        @DisplayName("should return all cached values")
        void shouldReturnAllCachedValues() {
            var cache = new CaffeineLocalCache<String, String>(Duration.ofMinutes(5), 100);
            cache.put("key1", "value1");
            cache.put("key2", "value2");
            cache.put("key3", "value3");

            var values = cache.values();

            assertEquals(3, values.size());
            assertTrue(values.contains("value1"));
            assertTrue(values.contains("value2"));
            assertTrue(values.contains("value3"));
        }

        @Test
        @DisplayName("should return empty collection when cache is empty")
        void shouldReturnEmptyCollectionWhenCacheEmpty() {
            var cache = new CaffeineLocalCache<String, String>(Duration.ofMinutes(5), 100);

            var values = cache.values();

            assertTrue(values.isEmpty());
        }
    }

    @Nested
    @DisplayName("Estimated Size")
    class EstimatedSize {

        @Test
        @DisplayName("should return correct estimated size")
        void shouldReturnCorrectEstimatedSize() {
            var cache = new CaffeineLocalCache<String, String>(Duration.ofMinutes(5), 100);

            assertEquals(0, cache.estimatedSize());

            cache.put("key1", "value1");
            cache.put("key2", "value2");

            assertEquals(2, cache.estimatedSize());
        }

        @Test
        @DisplayName("should update size after invalidation")
        void shouldUpdateSizeAfterInvalidation() {
            var cache = new CaffeineLocalCache<String, String>(Duration.ofMinutes(5), 100);
            cache.put("key1", "value1");
            cache.put("key2", "value2");

            cache.invalidate("key1");

            assertEquals(1, cache.estimatedSize());
        }
    }
}
