package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.storage.memory.InMemoryApiKeyRepository;
import aussie.config.ApiKeyConfig;
import aussie.core.model.Permissions;

@DisplayName("ApiKeyService")
class ApiKeyServiceTest {

    private ApiKeyService apiKeyService;
    private InMemoryApiKeyRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryApiKeyRepository();
        // Create a config that has no max TTL restriction
        ApiKeyConfig config = () -> Optional.empty();
        apiKeyService = new ApiKeyService(repository, config);
    }

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("should create key with specified name and permissions")
        void shouldCreateKeyWithNameAndPermissions() {
            var result = apiKeyService.create(
                    "test-key", "Test description", Set.of(Permissions.ADMIN_READ, Permissions.ADMIN_WRITE), null);

            assertNotNull(result.keyId());
            assertNotNull(result.plaintextKey());
            assertEquals("test-key", result.metadata().name());
            assertEquals("Test description", result.metadata().description());
            assertTrue(result.metadata().permissions().contains(Permissions.ADMIN_READ));
            assertTrue(result.metadata().permissions().contains(Permissions.ADMIN_WRITE));
        }

        @Test
        @DisplayName("should create key with TTL")
        void shouldCreateKeyWithTtl() {
            var result = apiKeyService.create("expiring-key", null, Set.of(), Duration.ofDays(30));

            assertNotNull(result.metadata().expiresAt());
        }

        @Test
        @DisplayName("should create key without expiration when TTL is null")
        void shouldCreateKeyWithoutExpirationWhenTtlIsNull() {
            var result = apiKeyService.create("permanent-key", null, Set.of(), null);

            assertTrue(result.metadata().expiresAt() == null);
        }

        @Test
        @DisplayName("should generate unique key IDs")
        void shouldGenerateUniqueKeyIds() {
            var result1 = apiKeyService.create("key1", null, Set.of(), null);
            var result2 = apiKeyService.create("key2", null, Set.of(), null);

            assertNotEquals(result1.keyId(), result2.keyId());
        }

        @Test
        @DisplayName("should generate unique plaintext keys")
        void shouldGenerateUniquePlaintextKeys() {
            var result1 = apiKeyService.create("key1", null, Set.of(), null);
            var result2 = apiKeyService.create("key2", null, Set.of(), null);

            assertNotEquals(result1.plaintextKey(), result2.plaintextKey());
        }
    }

    @Nested
    @DisplayName("validate")
    class ValidateTests {

        @Test
        @DisplayName("should validate existing key")
        void shouldValidateExistingKey() {
            var createResult = apiKeyService.create("valid-key", null, Set.of(Permissions.ADMIN_READ), null);

            var validateResult = apiKeyService.validate(createResult.plaintextKey());

            assertTrue(validateResult.isPresent());
            assertEquals(createResult.keyId(), validateResult.get().id());
        }

        @Test
        @DisplayName("should return empty for non-existent key")
        void shouldReturnEmptyForNonExistentKey() {
            var result = apiKeyService.validate("non-existent-key");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty for null key")
        void shouldReturnEmptyForNullKey() {
            var result = apiKeyService.validate(null);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty for blank key")
        void shouldReturnEmptyForBlankKey() {
            var result = apiKeyService.validate("   ");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty for revoked key")
        void shouldReturnEmptyForRevokedKey() {
            var createResult = apiKeyService.create("to-revoke", null, Set.of(), null);
            apiKeyService.revoke(createResult.keyId());

            var validateResult = apiKeyService.validate(createResult.plaintextKey());

            assertTrue(validateResult.isEmpty());
        }
    }

    @Nested
    @DisplayName("list")
    class ListTests {

        @Test
        @DisplayName("should return empty list when no keys exist")
        void shouldReturnEmptyListWhenNoKeysExist() {
            var result = apiKeyService.list();

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return all keys with redacted hashes")
        void shouldReturnAllKeysWithRedactedHashes() {
            apiKeyService.create("key1", null, Set.of(), null);
            apiKeyService.create("key2", null, Set.of(), null);

            var result = apiKeyService.list();

            assertEquals(2, result.size());
            assertTrue(result.stream().allMatch(k -> k.keyHash().equals("[REDACTED]")));
        }
    }

    @Nested
    @DisplayName("revoke")
    class RevokeTests {

        @Test
        @DisplayName("should revoke existing key")
        void shouldRevokeExistingKey() {
            var createResult = apiKeyService.create("to-revoke", null, Set.of(), null);

            boolean result = apiKeyService.revoke(createResult.keyId());

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when key does not exist")
        void shouldReturnFalseWhenKeyDoesNotExist() {
            boolean result = apiKeyService.revoke("non-existent");

            assertFalse(result);
        }

        @Test
        @DisplayName("revoked key should appear in list as revoked")
        void revokedKeyShouldAppearInListAsRevoked() {
            var createResult = apiKeyService.create("to-revoke", null, Set.of(), null);
            apiKeyService.revoke(createResult.keyId());

            var list = apiKeyService.list();
            var revokedKey = list.stream()
                    .filter(k -> k.id().equals(createResult.keyId()))
                    .findFirst();

            assertTrue(revokedKey.isPresent());
            assertTrue(revokedKey.get().revoked());
        }
    }

    @Nested
    @DisplayName("get")
    class GetTests {

        @Test
        @DisplayName("should return key with redacted hash")
        void shouldReturnKeyWithRedactedHash() {
            var createResult = apiKeyService.create("get-test", null, Set.of(), null);

            var result = apiKeyService.get(createResult.keyId());

            assertTrue(result.isPresent());
            assertEquals("[REDACTED]", result.get().keyHash());
        }

        @Test
        @DisplayName("should return empty for non-existent key")
        void shouldReturnEmptyForNonExistentKey() {
            var result = apiKeyService.get("non-existent");

            assertTrue(result.isEmpty());
        }
    }
}
