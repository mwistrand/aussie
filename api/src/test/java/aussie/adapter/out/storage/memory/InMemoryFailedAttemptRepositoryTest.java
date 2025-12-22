package aussie.adapter.out.storage.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryFailedAttemptRepository")
class InMemoryFailedAttemptRepositoryTest {

    private InMemoryFailedAttemptRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryFailedAttemptRepository();
    }

    @AfterEach
    void tearDown() {
        repository.shutdown();
    }

    @Nested
    @DisplayName("recordFailedAttempt()")
    class RecordFailedAttemptTests {

        @Test
        @DisplayName("should increment counter atomically")
        void shouldIncrementCounterAtomically() {
            final var key = "ip:192.168.1.1";
            final var window = Duration.ofMinutes(10);

            final var count1 =
                    repository.recordFailedAttempt(key, window).await().atMost(Duration.ofSeconds(1));
            final var count2 =
                    repository.recordFailedAttempt(key, window).await().atMost(Duration.ofSeconds(1));
            final var count3 =
                    repository.recordFailedAttempt(key, window).await().atMost(Duration.ofSeconds(1));

            assertEquals(1L, count1);
            assertEquals(2L, count2);
            assertEquals(3L, count3);
        }

        @Test
        @DisplayName("should reset counter when window expires")
        void shouldResetCounterWhenWindowExpires() {
            final var key = "ip:192.168.1.1";
            final var window = Duration.ofMillis(50);

            repository.recordFailedAttempt(key, window).await().atMost(Duration.ofSeconds(1));
            repository.recordFailedAttempt(key, window).await().atMost(Duration.ofSeconds(1));

            // Wait for window to expire
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            final var count =
                    repository.recordFailedAttempt(key, window).await().atMost(Duration.ofSeconds(1));
            assertEquals(1L, count);
        }

        @Test
        @DisplayName("should track different keys independently")
        void shouldTrackDifferentKeysIndependently() {
            final var key1 = "ip:192.168.1.1";
            final var key2 = "user:john@example.com";
            final var window = Duration.ofMinutes(10);

            repository.recordFailedAttempt(key1, window).await().atMost(Duration.ofSeconds(1));
            repository.recordFailedAttempt(key1, window).await().atMost(Duration.ofSeconds(1));
            repository.recordFailedAttempt(key2, window).await().atMost(Duration.ofSeconds(1));

            assertEquals(2, repository.getFailedAttemptKeyCount());
        }
    }

    @Nested
    @DisplayName("getFailedAttemptCount()")
    class GetFailedAttemptCountTests {

        @Test
        @DisplayName("should return 0 for non-existent key")
        void shouldReturnZeroForNonExistentKey() {
            final var count =
                    repository.getFailedAttemptCount("unknown").await().atMost(Duration.ofSeconds(1));
            assertEquals(0L, count);
        }

        @Test
        @DisplayName("should return current count")
        void shouldReturnCurrentCount() {
            final var key = "ip:192.168.1.1";
            final var window = Duration.ofMinutes(10);

            repository.recordFailedAttempt(key, window).await().atMost(Duration.ofSeconds(1));
            repository.recordFailedAttempt(key, window).await().atMost(Duration.ofSeconds(1));

            final var count = repository.getFailedAttemptCount(key).await().atMost(Duration.ofSeconds(1));
            assertEquals(2L, count);
        }

        @Test
        @DisplayName("should return 0 for expired entry")
        void shouldReturnZeroForExpiredEntry() {
            final var key = "ip:192.168.1.1";
            final var window = Duration.ofMillis(50);

            repository.recordFailedAttempt(key, window).await().atMost(Duration.ofSeconds(1));

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            final var count = repository.getFailedAttemptCount(key).await().atMost(Duration.ofSeconds(1));
            assertEquals(0L, count);
        }
    }

    @Nested
    @DisplayName("clearFailedAttempts()")
    class ClearFailedAttemptsTests {

        @Test
        @DisplayName("should remove entry")
        void shouldRemoveEntry() {
            final var key = "ip:192.168.1.1";
            final var window = Duration.ofMinutes(10);

            repository.recordFailedAttempt(key, window).await().atMost(Duration.ofSeconds(1));
            repository.clearFailedAttempts(key).await().atMost(Duration.ofSeconds(1));

            final var count = repository.getFailedAttemptCount(key).await().atMost(Duration.ofSeconds(1));
            assertEquals(0L, count);
        }
    }

    @Nested
    @DisplayName("recordLockout()")
    class RecordLockoutTests {

        @Test
        @DisplayName("should store lockout with expiry")
        void shouldStoreLockoutWithExpiry() {
            final var key = "ip:192.168.1.1";
            final var duration = Duration.ofMinutes(15);

            repository
                    .recordLockout(key, duration, "max_failed_attempts")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var isLocked = repository.isLockedOut(key).await().atMost(Duration.ofSeconds(1));
            assertTrue(isLocked);
            assertEquals(1, repository.getLockoutKeyCount());
        }

        @Test
        @DisplayName("should increment lockout count")
        void shouldIncrementLockoutCount() {
            final var key = "ip:192.168.1.1";
            final var duration = Duration.ofMinutes(15);

            repository.recordLockout(key, duration, "test").await().atMost(Duration.ofSeconds(1));
            final var count1 = repository.getLockoutCount(key).await().atMost(Duration.ofSeconds(1));

            repository.recordLockout(key, duration, "test").await().atMost(Duration.ofSeconds(1));
            final var count2 = repository.getLockoutCount(key).await().atMost(Duration.ofSeconds(1));

            assertEquals(1, count1);
            assertEquals(2, count2);
        }
    }

    @Nested
    @DisplayName("isLockedOut()")
    class IsLockedOutTests {

        @Test
        @DisplayName("should return true for active lockout")
        void shouldReturnTrueForActiveLockout() {
            final var key = "ip:192.168.1.1";
            repository
                    .recordLockout(key, Duration.ofMinutes(15), "test")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var result = repository.isLockedOut(key).await().atMost(Duration.ofSeconds(1));
            assertTrue(result);
        }

        @Test
        @DisplayName("should return false for non-existent key")
        void shouldReturnFalseForNonExistentKey() {
            final var result = repository.isLockedOut("unknown").await().atMost(Duration.ofSeconds(1));
            assertFalse(result);
        }

        @Test
        @DisplayName("should return false for expired lockout")
        void shouldReturnFalseForExpiredLockout() {
            final var key = "ip:192.168.1.1";
            repository.recordLockout(key, Duration.ofMillis(50), "test").await().atMost(Duration.ofSeconds(1));

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            final var result = repository.isLockedOut(key).await().atMost(Duration.ofSeconds(1));
            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("getLockoutExpiry()")
    class GetLockoutExpiryTests {

        @Test
        @DisplayName("should return expiry for active lockout")
        void shouldReturnExpiryForActiveLockout() {
            final var key = "ip:192.168.1.1";
            final var now = Instant.now();
            repository
                    .recordLockout(key, Duration.ofMinutes(15), "test")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var expiry = repository.getLockoutExpiry(key).await().atMost(Duration.ofSeconds(1));

            assertNotNull(expiry);
            assertTrue(expiry.isAfter(now));
        }

        @Test
        @DisplayName("should return null for non-existent key")
        void shouldReturnNullForNonExistentKey() {
            final var expiry = repository.getLockoutExpiry("unknown").await().atMost(Duration.ofSeconds(1));
            assertNull(expiry);
        }
    }

    @Nested
    @DisplayName("clearLockout()")
    class ClearLockoutTests {

        @Test
        @DisplayName("should remove lockout")
        void shouldRemoveLockout() {
            final var key = "ip:192.168.1.1";
            repository
                    .recordLockout(key, Duration.ofMinutes(15), "test")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            repository.clearLockout(key).await().atMost(Duration.ofSeconds(1));

            final var isLocked = repository.isLockedOut(key).await().atMost(Duration.ofSeconds(1));
            assertFalse(isLocked);
        }
    }

    @Nested
    @DisplayName("streamAllLockouts()")
    class StreamAllLockoutsTests {

        @Test
        @DisplayName("should return all active lockouts")
        void shouldReturnAllActiveLockouts() {
            repository
                    .recordLockout("ip:1.1.1.1", Duration.ofMinutes(15), "test")
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .recordLockout("ip:2.2.2.2", Duration.ofMinutes(15), "test")
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .recordLockout("user:john", Duration.ofMinutes(15), "test")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var lockouts =
                    repository.streamAllLockouts().collect().asList().await().atMost(Duration.ofSeconds(1));

            assertEquals(3, lockouts.size());
        }

        @Test
        @DisplayName("should filter out expired lockouts")
        void shouldFilterOutExpiredLockouts() {
            repository
                    .recordLockout("ip:1.1.1.1", Duration.ofMinutes(15), "test")
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .recordLockout("ip:2.2.2.2", Duration.ofMillis(50), "test")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            final var lockouts =
                    repository.streamAllLockouts().collect().asList().await().atMost(Duration.ofSeconds(1));

            assertEquals(1, lockouts.size());
            assertEquals("ip:1.1.1.1", lockouts.get(0).key());
        }

        @Test
        @DisplayName("should return empty when no lockouts")
        void shouldReturnEmptyWhenNoLockouts() {
            final var lockouts =
                    repository.streamAllLockouts().collect().asList().await().atMost(Duration.ofSeconds(1));

            assertTrue(lockouts.isEmpty());
        }
    }

    @Nested
    @DisplayName("clear()")
    class ClearTests {

        @Test
        @DisplayName("should clear all entries")
        void shouldClearAllEntries() {
            repository
                    .recordFailedAttempt("ip:1.1.1.1", Duration.ofMinutes(10))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .recordLockout("ip:2.2.2.2", Duration.ofMinutes(15), "test")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            repository.clear();

            assertEquals(0, repository.getFailedAttemptKeyCount());
            assertEquals(0, repository.getLockoutKeyCount());
        }
    }
}
