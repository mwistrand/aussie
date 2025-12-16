package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.Group;

@DisplayName("GroupEncryptionService")
class GroupEncryptionServiceTest {

    /**
     * Generate a valid 256-bit key for testing.
     */
    private static String generateTestKey() {
        final byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Nested
    @DisplayName("constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should enable encryption when key provided")
        void shouldEnableEncryptionWhenKeyProvided() {
            final var service = new GroupEncryptionService(Optional.of(generateTestKey()), "v1", false);

            assertTrue(service.isEncryptionEnabled());
        }

        @Test
        @DisplayName("should disable encryption when no key provided")
        void shouldDisableEncryptionWhenNoKey() {
            final var service = new GroupEncryptionService(Optional.empty(), "v1", false);

            assertFalse(service.isEncryptionEnabled());
        }

        @Test
        @DisplayName("should disable encryption when key is blank")
        void shouldDisableEncryptionWhenKeyIsBlank() {
            final var service = new GroupEncryptionService(Optional.of("   "), "v1", false);

            assertFalse(service.isEncryptionEnabled());
        }

        @Test
        @DisplayName("should reject invalid key length")
        void shouldRejectInvalidKeyLength() {
            // 128-bit key (too short)
            final byte[] shortKey = new byte[16];
            new SecureRandom().nextBytes(shortKey);
            final String invalidKey = Base64.getEncoder().encodeToString(shortKey);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> new GroupEncryptionService(Optional.of(invalidKey), "v1", false));
        }
    }

    @Nested
    @DisplayName("encrypt() and decrypt() with encryption enabled")
    class EncryptionEnabledTests {

        private final GroupEncryptionService service =
                new GroupEncryptionService(Optional.of(generateTestKey()), "v1", false);

        @Test
        @DisplayName("should round-trip a group")
        void shouldRoundTripGroup() {
            final var original = Group.builder("developers")
                    .displayName("Developers")
                    .description("Development team")
                    .permissions(Set.of("apikeys.read", "service.config.read"))
                    .createdAt(Instant.parse("2024-01-15T10:30:00Z"))
                    .updatedAt(Instant.parse("2024-01-15T12:00:00Z"))
                    .build();

            final String encrypted = service.encrypt(original);
            final Group decrypted = service.decrypt(encrypted);

            assertEquals(original.id(), decrypted.id());
            assertEquals(original.displayName(), decrypted.displayName());
            assertEquals(original.description(), decrypted.description());
            assertEquals(original.permissions(), decrypted.permissions());
            assertEquals(original.createdAt(), decrypted.createdAt());
            assertEquals(original.updatedAt(), decrypted.updatedAt());
        }

        @Test
        @DisplayName("should produce different ciphertext for same group")
        void shouldProduceDifferentCiphertext() {
            final var group = Group.create("test", "Test", Set.of("perm1"));

            final String encrypted1 = service.encrypt(group);
            final String encrypted2 = service.encrypt(group);

            // Due to random IV, same plaintext should produce different ciphertext
            assertNotEquals(encrypted1, encrypted2);
        }

        @Test
        @DisplayName("should handle empty permissions")
        void shouldHandleEmptyPermissions() {
            final var group = Group.builder("empty-perms")
                    .displayName("Empty Permissions")
                    .permissions(Set.of())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            final String encrypted = service.encrypt(group);
            final Group decrypted = service.decrypt(encrypted);

            assertTrue(decrypted.permissions().isEmpty());
        }

        @Test
        @DisplayName("should handle null description")
        void shouldHandleNullDescription() {
            final var group = Group.builder("no-desc")
                    .displayName("No Description")
                    .description(null)
                    .permissions(Set.of("perm"))
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            final String encrypted = service.encrypt(group);
            final Group decrypted = service.decrypt(encrypted);

            // Null is stored as empty string, so expect empty or null
            assertTrue(
                    decrypted.description() == null || decrypted.description().isEmpty());
        }

        @Test
        @DisplayName("should handle special characters in fields")
        void shouldHandleSpecialCharacters() {
            // Note: \u0000 cannot be used as it's the field separator in serialization
            final var group = Group.builder("special-chars")
                    .displayName("Test with special chars: @#$%^&*()")
                    .description("Description with \"quotes\" and 'apostrophes'")
                    .permissions(Set.of("perm.with.dots", "perm:with:colons"))
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            final String encrypted = service.encrypt(group);
            final Group decrypted = service.decrypt(encrypted);

            assertEquals("special-chars", decrypted.id());
            assertEquals("Test with special chars: @#$%^&*()", decrypted.displayName());
        }

        @Test
        @DisplayName("should produce non-plaintext output")
        void shouldProduceNonPlaintextOutput() {
            final var group = Group.create("test", "Test Group", Set.of("permission"));

            final String encrypted = service.encrypt(group);

            // Should be base64 encoded and not start with PLAIN:
            assertFalse(encrypted.startsWith("PLAIN:"));
            assertFalse(encrypted.contains("test"));
            assertFalse(encrypted.contains("Test Group"));
        }
    }

    @Nested
    @DisplayName("encrypt() and decrypt() with encryption disabled")
    class EncryptionDisabledTests {

        private final GroupEncryptionService service = new GroupEncryptionService(Optional.empty(), "v1", false);

        @Test
        @DisplayName("should round-trip in plaintext mode")
        void shouldRoundTripInPlaintextMode() {
            final var original = Group.builder("plain-test")
                    .displayName("Plain Test")
                    .description("Testing plaintext")
                    .permissions(Set.of("perm1", "perm2"))
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            final String encoded = service.encrypt(original);
            final Group decoded = service.decrypt(encoded);

            assertEquals(original.id(), decoded.id());
            assertEquals(original.displayName(), decoded.displayName());
            assertEquals(original.permissions(), decoded.permissions());
        }

        @Test
        @DisplayName("should prefix output with PLAIN:")
        void shouldPrefixWithPlain() {
            final var group = Group.create("test", "Test", Set.of());

            final String encoded = service.encrypt(group);

            assertTrue(encoded.startsWith("PLAIN:"));
        }

        @Test
        @DisplayName("should throw when trying to decrypt encrypted data")
        void shouldThrowWhenDecryptingEncryptedData() {
            // First encrypt with enabled service
            final var enabledService = new GroupEncryptionService(Optional.of(generateTestKey()), "v1", false);
            final var group = Group.create("test", "Test", Set.of());
            final String encrypted = enabledService.encrypt(group);

            // Try to decrypt with disabled service
            assertThrows(IllegalStateException.class, () -> service.decrypt(encrypted));
        }
    }

    @Nested
    @DisplayName("key rotation")
    class KeyRotationTests {

        @Test
        @DisplayName("should decrypt data from different key ID with warning")
        void shouldDecryptWithDifferentKeyId() {
            // Create service with key ID v1
            final String key = generateTestKey();
            final var serviceV1 = new GroupEncryptionService(Optional.of(key), "v1", false);
            final var group = Group.create("rotation-test", "Test", Set.of("perm"));
            final String encrypted = serviceV1.encrypt(group);

            // Create service with key ID v2 but same key
            final var serviceV2 = new GroupEncryptionService(Optional.of(key), "v2", false);

            // Should still decrypt (with warning in logs)
            final Group decrypted = serviceV2.decrypt(encrypted);
            assertEquals("rotation-test", decrypted.id());
        }
    }

    @Nested
    @DisplayName("cross-compatibility")
    class CrossCompatibilityTests {

        @Test
        @DisplayName("encrypted service should decrypt plaintext data")
        void encryptedServiceShouldDecryptPlaintext() {
            final var disabledService = new GroupEncryptionService(Optional.empty(), "v1", false);
            final var enabledService = new GroupEncryptionService(Optional.of(generateTestKey()), "v1", false);

            final var group = Group.create("compat-test", "Test", Set.of("perm"));
            final String plainEncoded = disabledService.encrypt(group);

            // Enabled service should be able to decrypt PLAIN: data
            final Group decrypted = enabledService.decrypt(plainEncoded);
            assertEquals("compat-test", decrypted.id());
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCaseTests {

        private final GroupEncryptionService service =
                new GroupEncryptionService(Optional.of(generateTestKey()), "v1", false);

        @Test
        @DisplayName("should handle group with many permissions")
        void shouldHandleManyPermissions() {
            final Set<String> manyPermissions = Set.of(
                    "apikeys.read",
                    "apikeys.write",
                    "apikeys.delete",
                    "service.config.read",
                    "service.config.write",
                    "users.read",
                    "users.write",
                    "users.admin",
                    "billing.read",
                    "billing.write",
                    "analytics.read",
                    "analytics.export");

            final var group = Group.builder("power-users")
                    .displayName("Power Users")
                    .permissions(manyPermissions)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            final String encrypted = service.encrypt(group);
            final Group decrypted = service.decrypt(encrypted);

            assertEquals(manyPermissions.size(), decrypted.permissions().size());
            assertTrue(decrypted.permissions().containsAll(manyPermissions));
        }

        @Test
        @DisplayName("should handle unicode in group fields")
        void shouldHandleUnicode() {
            final var group = Group.builder("unicode-test")
                    .displayName("Développeurs")
                    .description("团队描述")
                    .permissions(Set.of("権限.読み取り"))
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();

            final String encrypted = service.encrypt(group);
            final Group decrypted = service.decrypt(encrypted);

            assertEquals("Développeurs", decrypted.displayName());
            assertEquals("团队描述", decrypted.description());
            assertTrue(decrypted.permissions().contains("権限.読み取り"));
        }
    }
}
