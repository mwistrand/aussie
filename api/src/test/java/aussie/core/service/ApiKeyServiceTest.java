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
import aussie.core.config.ApiKeyConfig;
import aussie.core.model.auth.Permission;
import aussie.core.service.auth.ApiKeyService;

@DisplayName("ApiKeyService")
class ApiKeyServiceTest {

    private static final String VERSIONED_KEY = ApiKeyService.API_KEY_PREFIX + "A".repeat(43);
    private ApiKeyService apiKeyService;
    private InMemoryApiKeyRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryApiKeyRepository();
        // Create a config that has no max TTL restriction
        ApiKeyConfig config = config(Optional.empty());
        apiKeyService = new ApiKeyService(repository, config);
    }

    private ApiKeyConfig config(Optional<Duration> maxTtl) {
        return new ApiKeyConfig() {
            @Override
            public boolean acceptLegacyFormat() {
                return false;
            }

            @Override
            public Optional<Duration> maxTtl() {
                return maxTtl;
            }
        };
    }

    @Nested
    @DisplayName("create")
    class CreateTests {

        @Test
        @DisplayName("should create key with specified name and permissions")
        void shouldCreateKeyWithNameAndPermissions() {
            var result = apiKeyService
                    .create(
                            "test-key",
                            "Test description",
                            null,
                            Set.of(
                                    Permission.SERVICE_CONFIG_READ_VALUE,
                                    Permission.SERVICE_CONFIG_CREATE_VALUE,
                                    Permission.SERVICE_CONFIG_UPDATE_VALUE,
                                    Permission.SERVICE_CONFIG_DELETE_VALUE,
                                    "demo-service.admin"),
                            null,
                            "test-creator")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNotNull(result.keyId());
            assertNotNull(result.plaintextKey());
            assertTrue(ApiKeyService.isVersionedKey(result.plaintextKey()));
            assertEquals("test-key", result.metadata().name());
            assertEquals("Test description", result.metadata().description());
            assertEquals("test-creator", result.metadata().createdBy());
            assertTrue(result.metadata().permissions().contains(Permission.SERVICE_CONFIG_READ_VALUE));
            assertTrue(result.metadata().permissions().contains(Permission.SERVICE_CONFIG_CREATE_VALUE));
            assertTrue(result.metadata().permissions().contains(Permission.SERVICE_CONFIG_UPDATE_VALUE));
            assertTrue(result.metadata().permissions().contains(Permission.SERVICE_CONFIG_DELETE_VALUE));
            assertTrue(result.metadata().permissions().contains("demo-service.admin"));
        }

        @Test
        @DisplayName("should create key with TTL")
        void shouldCreateKeyWithTtl() {
            var result = apiKeyService
                    .create("expiring-key", null, null, Set.of(), Duration.ofDays(30), "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNotNull(result.metadata().expiresAt());
        }

        @Test
        @DisplayName("should create key without expiration when TTL is null")
        void shouldCreateKeyWithoutExpirationWhenTtlIsNull() {
            var result = apiKeyService
                    .create("permanent-key", null, null, Set.of(), null, "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertTrue(result.metadata().expiresAt() == null);
        }

        @Test
        @DisplayName("should generate unique key IDs")
        void shouldGenerateUniqueKeyIds() {
            var result1 = apiKeyService
                    .create("key1", null, null, Set.of(), null, "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));
            var result2 = apiKeyService
                    .create("key2", null, null, Set.of(), null, "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNotEquals(result1.keyId(), result2.keyId());
        }

        @Test
        @DisplayName("should generate unique plaintext keys")
        void shouldGenerateUniquePlaintextKeys() {
            var result1 = apiKeyService
                    .create("key1", null, null, Set.of(), null, "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));
            var result2 = apiKeyService
                    .create("key2", null, null, Set.of(), null, "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNotEquals(result1.plaintextKey(), result2.plaintextKey());
        }

        @Test
        @DisplayName("should create key with team ID")
        void shouldCreateKeyWithTeamId() {
            var result = apiKeyService
                    .create("team-key", "Team key", "platform-team", Set.of(), null, "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals("platform-team", result.metadata().teamId());

            // Validate the key and ensure teamId is preserved
            var validated =
                    apiKeyService.validate(result.plaintextKey()).await().atMost(Duration.ofSeconds(5));
            assertTrue(validated.isPresent());
            assertEquals("platform-team", validated.get().teamId());
        }
    }

    @Nested
    @DisplayName("validate")
    class ValidateTests {

        @Test
        @DisplayName("should validate existing key")
        void shouldValidateExistingKey() {
            var createResult = apiKeyService
                    .create("valid-key", null, null, Set.of(Permission.SERVICE_CONFIG_READ_VALUE), null, "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            var validateResult =
                    apiKeyService.validate(createResult.plaintextKey()).await().atMost(Duration.ofSeconds(5));

            assertTrue(validateResult.isPresent());
            assertEquals(createResult.keyId(), validateResult.get().id());
        }

        @Test
        @DisplayName("should return empty for non-existent key")
        void shouldReturnEmptyForNonExistentKey() {
            var result = apiKeyService.validate("non-existent-key").await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty for null key")
        void shouldReturnEmptyForNullKey() {
            var result = apiKeyService.validate(null).await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty for blank key")
        void shouldReturnEmptyForBlankKey() {
            var result = apiKeyService.validate("   ").await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty for revoked key")
        void shouldReturnEmptyForRevokedKey() {
            var createResult = apiKeyService
                    .create("to-revoke", null, null, Set.of(), null, "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));
            apiKeyService.revoke(createResult.keyId()).await().atMost(Duration.ofSeconds(5));

            var validateResult =
                    apiKeyService.validate(createResult.plaintextKey()).await().atMost(Duration.ofSeconds(5));

            assertTrue(validateResult.isEmpty());
        }
    }

    @Nested
    @DisplayName("list")
    class ListTests {

        @Test
        @DisplayName("should return empty list when no keys exist")
        void shouldReturnEmptyListWhenNoKeysExist() {
            var result = apiKeyService.list().await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return all keys with redacted hashes")
        void shouldReturnAllKeysWithRedactedHashes() {
            apiKeyService
                    .create("key1", null, null, Set.of(), null, "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));
            apiKeyService
                    .create("key2", null, null, Set.of(), null, "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            var result = apiKeyService.list().await().atMost(Duration.ofSeconds(5));

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
            var createResult = apiKeyService
                    .create("to-revoke", null, null, Set.of(), null, "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            boolean result = apiKeyService.revoke(createResult.keyId()).await().atMost(Duration.ofSeconds(5));

            assertTrue(result);
        }

        @Test
        @DisplayName("should not advance the version when revoking an already-revoked key")
        void shouldKeepRepeatedRevocationIdempotent() {
            var createResult = apiKeyService
                    .create("to-revoke", null, null, Set.of(), null, "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertTrue(apiKeyService.revoke(createResult.keyId()).await().atMost(Duration.ofSeconds(5)));
            assertTrue(apiKeyService.revoke(createResult.keyId()).await().atMost(Duration.ofSeconds(5)));

            assertEquals(
                    2L,
                    apiKeyService
                            .get(createResult.keyId())
                            .await()
                            .atMost(Duration.ofSeconds(5))
                            .orElseThrow()
                            .version());
        }

        @Test
        @DisplayName("should return false when key does not exist")
        void shouldReturnFalseWhenKeyDoesNotExist() {
            boolean result = apiKeyService.revoke("non-existent").await().atMost(Duration.ofSeconds(5));

            assertFalse(result);
        }

        @Test
        @DisplayName("revoked key should appear in list as revoked")
        void revokedKeyShouldAppearInListAsRevoked() {
            var createResult = apiKeyService
                    .create("to-revoke", null, null, Set.of(), null, "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));
            apiKeyService.revoke(createResult.keyId()).await().atMost(Duration.ofSeconds(5));

            var list = apiKeyService.list().await().atMost(Duration.ofSeconds(5));
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
            var createResult = apiKeyService
                    .create("get-test", null, null, Set.of(), null, "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            var result = apiKeyService.get(createResult.keyId()).await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isPresent());
            assertEquals("[REDACTED]", result.get().keyHash());
        }

        @Test
        @DisplayName("should return empty for non-existent key")
        void shouldReturnEmptyForNonExistentKey() {
            var result = apiKeyService.get("non-existent").await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("createWithKey")
    class CreateWithKeyTests {

        @Test
        @DisplayName("should create key with specified plaintext key")
        void shouldCreateKeyWithSpecifiedPlaintextKey() {
            String specifiedKey = VERSIONED_KEY;

            var result = apiKeyService
                    .createWithKey(
                            "bootstrap-key",
                            "Bootstrap key for testing",
                            null,
                            Set.of(
                                    Permission.SERVICE_CONFIG_READ_VALUE,
                                    Permission.SERVICE_CONFIG_CREATE_VALUE,
                                    Permission.SERVICE_CONFIG_UPDATE_VALUE,
                                    Permission.SERVICE_CONFIG_DELETE_VALUE,
                                    "demo-service.admin"),
                            null,
                            specifiedKey,
                            "bootstrap")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNotNull(result.keyId());
            assertEquals(specifiedKey, result.plaintextKey());
            assertEquals("bootstrap-key", result.metadata().name());
            assertEquals("bootstrap", result.metadata().createdBy());
            assertTrue(result.metadata().permissions().contains("demo-service.admin"));
        }

        @Test
        @DisplayName("should validate key against provided plaintext")
        void shouldValidateKeyAgainstProvidedPlaintext() {
            String specifiedKey = VERSIONED_KEY;

            apiKeyService
                    .createWithKey(
                            "validate-test",
                            null,
                            null,
                            Set.of(Permission.SERVICE_CONFIG_READ_VALUE),
                            null,
                            specifiedKey,
                            "bootstrap")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            var validateResult = apiKeyService.validate(specifiedKey).await().atMost(Duration.ofSeconds(5));

            assertTrue(validateResult.isPresent());
            assertEquals("validate-test", validateResult.get().name());
        }

        @Test
        @DisplayName("should reject an unversioned key")
        void shouldRejectShortKey() {
            String shortKey = "too-short-key";

            var exception = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> apiKeyService.createWithKey(
                            "short-key-test", null, null, Set.of(), null, shortKey, "bootstrap"));

            assertTrue(exception.getMessage().contains("aussie_v1_"));
        }

        @Test
        @DisplayName("should reject non-Base64URL characters")
        void shouldRejectNonBase64UrlCharacters() {
            final var malformedKey = ApiKeyService.API_KEY_PREFIX + "A".repeat(42) + "é";

            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> apiKeyService.createWithKey(
                            "malformed-key-test", null, null, Set.of(), null, malformedKey, "bootstrap"));
        }

        @Test
        @DisplayName("should reject null key")
        void shouldRejectNullKey() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> apiKeyService.createWithKey("null-key-test", null, null, Set.of(), null, null, "bootstrap"));
        }

        @Test
        @DisplayName("should reject blank key")
        void shouldRejectBlankKey() {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> apiKeyService.createWithKey(
                            "blank-key-test", null, null, Set.of(), null, "   ", "bootstrap"));
        }

        @Test
        @DisplayName("should create key with TTL")
        void shouldCreateKeyWithTtl() {
            String specifiedKey = VERSIONED_KEY;

            var result = apiKeyService
                    .createWithKey("ttl-bootstrap", null, null, Set.of(), Duration.ofDays(7), specifiedKey, "bootstrap")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNotNull(result.metadata().expiresAt());
        }
    }

    @Nested
    @DisplayName("TTL validation")
    class TtlValidationTests {

        @Test
        @DisplayName("should enforce max TTL when configured")
        void shouldEnforceMaxTtlWhenConfigured() {
            // Create service with max TTL of 30 days
            ApiKeyConfig restrictedConfig = config(Optional.of(Duration.ofDays(30)));
            var restrictedService = new ApiKeyService(repository, restrictedConfig);

            // Request TTL longer than max should fail
            var exception = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> restrictedService.create("long-ttl-key", null, null, Set.of(), Duration.ofDays(60), "test"));

            assertTrue(exception.getMessage().contains("exceeds maximum"));
        }

        @Test
        @DisplayName("should allow TTL within max")
        void shouldAllowTtlWithinMax() {
            ApiKeyConfig restrictedConfig = config(Optional.of(Duration.ofDays(30)));
            var restrictedService = new ApiKeyService(repository, restrictedConfig);

            // Request TTL shorter than max should succeed
            var result = restrictedService
                    .create("valid-ttl-key", null, null, Set.of(), Duration.ofDays(7), "test")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNotNull(result.keyId());
        }

        @Test
        @DisplayName("should require TTL when max is configured")
        void shouldRequireTtlWhenMaxIsConfigured() {
            ApiKeyConfig restrictedConfig = config(Optional.of(Duration.ofDays(30)));
            var restrictedService = new ApiKeyService(repository, restrictedConfig);

            // Null TTL should fail when max is configured
            var exception = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> restrictedService.create("no-ttl-key", null, null, Set.of(), null, "test"));

            assertTrue(exception.getMessage().contains("TTL is required"));
        }
    }
}
