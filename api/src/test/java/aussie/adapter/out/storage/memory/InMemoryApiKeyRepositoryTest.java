package aussie.adapter.out.storage.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.ApiKey;

@DisplayName("InMemoryApiKeyRepository")
class InMemoryApiKeyRepositoryTest {

    private InMemoryApiKeyRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryApiKeyRepository();
    }

    private ApiKey createTestKey(String id, String keyHash) {
        return ApiKey.builder(id, keyHash)
                .name("test-key-" + id)
                .description("Test API key")
                .permissions(Set.of("read", "write"))
                .createdBy("test")
                .build();
    }

    @Nested
    @DisplayName("save()")
    class SaveTests {

        @Test
        @DisplayName("should save api key")
        void shouldSaveApiKey() {
            var key = createTestKey("key-1", "hash-1");

            repository.save(key).await().atMost(Duration.ofSeconds(1));

            var found = repository.findById("key-1").await().atMost(Duration.ofSeconds(1));
            assertTrue(found.isPresent());
            assertEquals("key-1", found.get().id());
        }

        @Test
        @DisplayName("should reject stale conditional replacement")
        void shouldRejectStaleConditionalReplacement() {
            final var original = createTestKey("key-1", "hash-1");
            repository.save(original).await().atMost(Duration.ofSeconds(1));

            final var revoked = original.revoke();
            assertTrue(repository
                    .replaceIfVersion(revoked, 1)
                    .await()
                    .atMost(Duration.ofSeconds(1))
                    .applied());
            assertFalse(repository
                    .replaceIfVersion(original, 1)
                    .await()
                    .atMost(Duration.ofSeconds(1))
                    .applied());
        }

        @Test
        @DisplayName("should overwrite existing key with same id")
        void shouldOverwriteExistingKeyWithSameId() {
            var key1 = createTestKey("key-1", "hash-1");
            var key2 = ApiKey.builder("key-1", "hash-2")
                    .name("updated-key")
                    .createdBy("test")
                    .build();

            repository.save(key1).await().atMost(Duration.ofSeconds(1));
            repository.save(key2).await().atMost(Duration.ofSeconds(1));

            var found = repository.findById("key-1").await().atMost(Duration.ofSeconds(1));
            assertTrue(found.isPresent());
            assertEquals("hash-2", found.get().keyHash());
        }

        @Test
        @DisplayName("should index by both id and hash")
        void shouldIndexByBothIdAndHash() {
            var key = createTestKey("key-1", "hash-1");

            repository.save(key).await().atMost(Duration.ofSeconds(1));

            assertTrue(repository
                    .findById("key-1")
                    .await()
                    .atMost(Duration.ofSeconds(1))
                    .isPresent());
            assertTrue(repository
                    .findByHash("hash-1")
                    .await()
                    .atMost(Duration.ofSeconds(1))
                    .isPresent());
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindByIdTests {

        @Test
        @DisplayName("should return key when found")
        void shouldReturnKeyWhenFound() {
            var key = createTestKey("key-1", "hash-1");
            repository.save(key).await().atMost(Duration.ofSeconds(1));

            var result = repository.findById("key-1").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals("key-1", result.get().id());
            assertEquals("hash-1", result.get().keyHash());
        }

        @Test
        @DisplayName("should return empty when not found")
        void shouldReturnEmptyWhenNotFound() {
            var result = repository.findById("non-existent").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("findByHash()")
    class FindByHashTests {

        @Test
        @DisplayName("should return key when found by hash")
        void shouldReturnKeyWhenFoundByHash() {
            var key = createTestKey("key-1", "hash-1");
            repository.save(key).await().atMost(Duration.ofSeconds(1));

            var result = repository.findByHash("hash-1").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            assertEquals("key-1", result.get().id());
        }

        @Test
        @DisplayName("should return empty when hash not found")
        void shouldReturnEmptyWhenHashNotFound() {
            var result = repository.findByHash("unknown-hash").await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("should return true and remove key when exists")
        void shouldReturnTrueAndRemoveKeyWhenExists() {
            var key = createTestKey("key-1", "hash-1");
            repository.save(key).await().atMost(Duration.ofSeconds(1));

            var result = repository.delete("key-1").await().atMost(Duration.ofSeconds(1));

            assertTrue(result);
            assertTrue(repository
                    .findById("key-1")
                    .await()
                    .atMost(Duration.ofSeconds(1))
                    .isEmpty());
        }

        @Test
        @DisplayName("should return false when key does not exist")
        void shouldReturnFalseWhenKeyDoesNotExist() {
            var result = repository.delete("non-existent").await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }

        @Test
        @DisplayName("should remove from both id and hash index")
        void shouldRemoveFromBothIdAndHashIndex() {
            var key = createTestKey("key-1", "hash-1");
            repository.save(key).await().atMost(Duration.ofSeconds(1));

            repository.delete("key-1").await().atMost(Duration.ofSeconds(1));

            assertTrue(repository
                    .findById("key-1")
                    .await()
                    .atMost(Duration.ofSeconds(1))
                    .isEmpty());
            assertTrue(repository
                    .findByHash("hash-1")
                    .await()
                    .atMost(Duration.ofSeconds(1))
                    .isEmpty());
        }
    }

    @Nested
    @DisplayName("findAll()")
    class FindAllTests {

        @Test
        @DisplayName("should return all saved keys")
        void shouldReturnAllSavedKeys() {
            repository.save(createTestKey("key-1", "hash-1")).await().atMost(Duration.ofSeconds(1));
            repository.save(createTestKey("key-2", "hash-2")).await().atMost(Duration.ofSeconds(1));
            repository.save(createTestKey("key-3", "hash-3")).await().atMost(Duration.ofSeconds(1));

            var result = repository.findAll().await().atMost(Duration.ofSeconds(1));

            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("should return empty list when no keys exist")
        void shouldReturnEmptyListWhenNoKeysExist() {
            var result = repository.findAll().await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return defensive copy")
        void shouldReturnDefensiveCopy() {
            repository.save(createTestKey("key-1", "hash-1")).await().atMost(Duration.ofSeconds(1));

            var result1 = repository.findAll().await().atMost(Duration.ofSeconds(1));
            var result2 = repository.findAll().await().atMost(Duration.ofSeconds(1));

            assertEquals(result1.size(), result2.size());
            // Modifying the returned list should not affect the repository
            result1.clear();
            assertEquals(
                    1,
                    repository.findAll().await().atMost(Duration.ofSeconds(1)).size());
        }
    }

    @Nested
    @DisplayName("exists()")
    class ExistsTests {

        @Test
        @DisplayName("should return true when key exists")
        void shouldReturnTrueWhenKeyExists() {
            repository.save(createTestKey("key-1", "hash-1")).await().atMost(Duration.ofSeconds(1));

            var result = repository.exists("key-1").await().atMost(Duration.ofSeconds(1));

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when key does not exist")
        void shouldReturnFalseWhenKeyDoesNotExist() {
            var result = repository.exists("non-existent").await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false after key is deleted")
        void shouldReturnFalseAfterKeyIsDeleted() {
            repository.save(createTestKey("key-1", "hash-1")).await().atMost(Duration.ofSeconds(1));
            repository.delete("key-1").await().atMost(Duration.ofSeconds(1));

            var result = repository.exists("key-1").await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }
    }
}
