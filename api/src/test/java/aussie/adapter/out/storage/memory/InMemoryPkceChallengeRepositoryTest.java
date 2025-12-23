package aussie.adapter.out.storage.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryPkceChallengeRepository")
class InMemoryPkceChallengeRepositoryTest {

    private InMemoryPkceChallengeRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryPkceChallengeRepository();
    }

    @AfterEach
    void tearDown() {
        repository.shutdown();
    }

    @Nested
    @DisplayName("store()")
    class StoreTests {

        @Test
        @DisplayName("should store challenge successfully")
        void shouldStoreChallengeSuccessfully() {
            String state = "test-state";
            String challenge = "test-challenge";

            repository.store(state, challenge, Duration.ofMinutes(10)).await().atMost(Duration.ofSeconds(1));

            assertEquals(1, repository.getChallengeCount());
        }

        @Test
        @DisplayName("should overwrite existing challenge for same state")
        void shouldOverwriteExistingChallenge() {
            String state = "test-state";

            repository
                    .store(state, "challenge-1", Duration.ofMinutes(10))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .store(state, "challenge-2", Duration.ofMinutes(10))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertEquals(1, repository.getChallengeCount());

            Optional<String> result = repository.consumeChallenge(state).await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals("challenge-2", result.get());
        }

        @Test
        @DisplayName("should store multiple challenges for different states")
        void shouldStoreMultipleChallenges() {
            repository
                    .store("state-1", "challenge-1", Duration.ofMinutes(10))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .store("state-2", "challenge-2", Duration.ofMinutes(10))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .store("state-3", "challenge-3", Duration.ofMinutes(10))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertEquals(3, repository.getChallengeCount());
        }
    }

    @Nested
    @DisplayName("consumeChallenge()")
    class ConsumeChallengeTests {

        @Test
        @DisplayName("should retrieve and delete challenge")
        void shouldRetrieveAndDeleteChallenge() {
            String state = "test-state";
            String challenge = "test-challenge";

            repository.store(state, challenge, Duration.ofMinutes(10)).await().atMost(Duration.ofSeconds(1));

            // First consumption should return the challenge
            Optional<String> result1 =
                    repository.consumeChallenge(state).await().atMost(Duration.ofSeconds(1));

            assertTrue(result1.isPresent());
            assertEquals(challenge, result1.get());
            assertEquals(0, repository.getChallengeCount());

            // Second consumption should return empty (already consumed)
            Optional<String> result2 =
                    repository.consumeChallenge(state).await().atMost(Duration.ofSeconds(1));

            assertFalse(result2.isPresent());
        }

        @Test
        @DisplayName("should return empty for non-existent state")
        void shouldReturnEmptyForNonExistentState() {
            Optional<String> result =
                    repository.consumeChallenge("unknown").await().atMost(Duration.ofSeconds(1));

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("should return empty for expired challenge")
        void shouldReturnEmptyForExpiredChallenge() {
            String state = "test-state";
            String challenge = "test-challenge";

            repository.store(state, challenge, Duration.ofMillis(50)).await().atMost(Duration.ofSeconds(1));

            // Wait for expiration
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            Optional<String> result = repository.consumeChallenge(state).await().atMost(Duration.ofSeconds(1));

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("should not affect other challenges")
        void shouldNotAffectOtherChallenges() {
            repository
                    .store("state-1", "challenge-1", Duration.ofMinutes(10))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .store("state-2", "challenge-2", Duration.ofMinutes(10))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            repository.consumeChallenge("state-1").await().atMost(Duration.ofSeconds(1));

            assertEquals(1, repository.getChallengeCount());

            Optional<String> result =
                    repository.consumeChallenge("state-2").await().atMost(Duration.ofSeconds(1));
            assertTrue(result.isPresent());
            assertEquals("challenge-2", result.get());
        }
    }

    @Nested
    @DisplayName("clear()")
    class ClearTests {

        @Test
        @DisplayName("should clear all challenges")
        void shouldClearAllChallenges() {
            repository
                    .store("state-1", "challenge-1", Duration.ofMinutes(10))
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .store("state-2", "challenge-2", Duration.ofMinutes(10))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            repository.clear();

            assertEquals(0, repository.getChallengeCount());
        }
    }

    @Nested
    @DisplayName("TTL expiration")
    class TtlExpirationTests {

        @Test
        @DisplayName("should retain challenge before TTL expires")
        void shouldRetainChallengeBeforeTtlExpires() {
            String state = "test-state";
            String challenge = "test-challenge";

            repository.store(state, challenge, Duration.ofSeconds(5)).await().atMost(Duration.ofSeconds(1));

            // Immediate retrieval should work
            Optional<String> result = repository.consumeChallenge(state).await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals(challenge, result.get());
        }

        @Test
        @DisplayName("should expire challenge after TTL")
        void shouldExpireChallengeAfterTtl() {
            String state = "test-state";
            String challenge = "test-challenge";

            repository.store(state, challenge, Duration.ofMillis(50)).await().atMost(Duration.ofSeconds(1));

            // Wait for TTL to expire
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            Optional<String> result = repository.consumeChallenge(state).await().atMost(Duration.ofSeconds(1));

            assertFalse(result.isPresent());
        }
    }
}
