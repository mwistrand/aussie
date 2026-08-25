package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.ApiKey;

@DisplayName("ApiKeyEncryptionService")
class ApiKeyEncryptionServiceTest {

    private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private ApiKeyEncryptionService createService(boolean encrypted) {
        if (encrypted) {
            return new ApiKeyEncryptionService(Optional.of(TEST_KEY), "v1", "test", false);
        }
        // Tests that round-trip the PLAIN: format need plaintext reads enabled.
        return new ApiKeyEncryptionService(Optional.empty(), "v1", "test", true);
    }

    @Nested
    @DisplayName("round-trip serialization")
    class RoundTrip {

        @Test
        @DisplayName("should round-trip ApiKey with teamId through plaintext")
        void shouldRoundTripWithTeamIdPlaintext() {
            var service = createService(false);
            var apiKey = ApiKey.builder("key-1", "hash-1")
                    .name("test-key")
                    .description("desc")
                    .teamId("platform-team")
                    .permissions(Set.of("admin:read"))
                    .createdBy("test")
                    .createdAt(Instant.parse("2025-01-01T00:00:00Z"))
                    .expiresAt(Instant.parse("2025-12-31T23:59:59Z"))
                    .version(4L)
                    .build();

            var encrypted = service.encrypt(apiKey);
            var decrypted = service.decrypt(encrypted);

            assertEquals("key-1", decrypted.id());
            assertEquals("hash-1", decrypted.keyHash());
            assertEquals("test-key", decrypted.name());
            assertEquals("desc", decrypted.description());
            assertEquals("platform-team", decrypted.teamId());
            assertTrue(decrypted.permissions().contains("admin:read"));
            assertEquals("test", decrypted.createdBy());
            assertEquals(Instant.parse("2025-01-01T00:00:00Z"), decrypted.createdAt());
            assertEquals(Instant.parse("2025-12-31T23:59:59Z"), decrypted.expiresAt());
            assertFalse(decrypted.revoked());
            assertEquals(4L, decrypted.version());
        }

        @Test
        @DisplayName("should round-trip ApiKey with teamId through encryption")
        void shouldRoundTripWithTeamIdEncrypted() {
            var service = createService(true);
            var apiKey = ApiKey.builder("key-2", "hash-2")
                    .name("encrypted-key")
                    .teamId("billing-team")
                    .permissions(Set.of("service:read", "service:write"))
                    .createdBy("admin")
                    .createdAt(Instant.parse("2025-06-01T12:00:00Z"))
                    .build();

            var encrypted = service.encrypt(apiKey);
            var decrypted = service.decrypt(encrypted);

            assertEquals("billing-team", decrypted.teamId());
            assertEquals("encrypted-key", decrypted.name());
            assertNull(decrypted.expiresAt());
        }

        @Test
        @DisplayName("should round-trip ApiKey with null teamId")
        void shouldRoundTripWithNullTeamId() {
            var service = createService(false);
            var apiKey = ApiKey.builder("key-3", "hash-3")
                    .name("no-team-key")
                    .createdBy("test")
                    .createdAt(Instant.parse("2025-01-01T00:00:00Z"))
                    .build();

            var encrypted = service.encrypt(apiKey);
            var decrypted = service.decrypt(encrypted);

            assertNull(decrypted.teamId());
        }
    }

    @Nested
    @DisplayName("backward-compatible deserialization")
    class BackwardCompatibility {

        private ApiKey decryptPlaintext(String serialized) {
            var service = createService(false);
            var encoded = "PLAIN:" + Base64.getEncoder().encodeToString(serialized.getBytes(StandardCharsets.UTF_8));
            return service.decrypt(encoded);
        }

        @Test
        @DisplayName("should deserialize 8-field format (v1: no createdBy, no teamId)")
        void shouldDeserialize8FieldFormat() {
            // v1 format: id, keyHash, name, description, permissions, createdAt, expiresAt, revoked
            var serialized = String.join(
                    "\u0000",
                    "id-1",
                    "hash-1",
                    "old-key",
                    "old desc",
                    "admin:read",
                    "2025-01-01T00:00:00Z",
                    "",
                    "false");

            var apiKey = decryptPlaintext(serialized);

            assertEquals("id-1", apiKey.id());
            assertEquals("old-key", apiKey.name());
            assertEquals("old desc", apiKey.description());
            assertEquals("unknown", apiKey.createdBy()); // compact constructor defaults null to "unknown"
            assertNull(apiKey.teamId());
            assertNull(apiKey.expiresAt());
            assertFalse(apiKey.revoked());
            assertEquals(1L, apiKey.version());
        }

        @Test
        @DisplayName("should deserialize 9-field format (v2: with createdBy, no teamId)")
        void shouldDeserialize9FieldFormat() {
            // v2 format: id, keyHash, name, description, permissions, createdBy, createdAt, expiresAt, revoked
            var serialized = String.join(
                    "\u0000",
                    "id-2",
                    "hash-2",
                    "v2-key",
                    "",
                    "service:read",
                    "admin-user",
                    "2025-06-01T00:00:00Z",
                    "2025-12-31T23:59:59Z",
                    "false");

            var apiKey = decryptPlaintext(serialized);

            assertEquals("id-2", apiKey.id());
            assertEquals("v2-key", apiKey.name());
            assertEquals("admin-user", apiKey.createdBy());
            assertNull(apiKey.teamId());
            assertNotNull(apiKey.expiresAt());
            assertFalse(apiKey.revoked());
        }

        @Test
        @DisplayName("should deserialize 10-field format (v3: with createdBy and teamId)")
        void shouldDeserialize10FieldFormat() {
            // v3 format: id, keyHash, name, description, permissions, createdBy, createdAt, expiresAt, revoked, teamId
            var serialized = String.join(
                    "\u0000",
                    "id-3",
                    "hash-3",
                    "v3-key",
                    "desc",
                    "service:read",
                    "bootstrap",
                    "2025-06-01T00:00:00Z",
                    "",
                    "false",
                    "platform-team");

            var apiKey = decryptPlaintext(serialized);

            assertEquals("id-3", apiKey.id());
            assertEquals("v3-key", apiKey.name());
            assertEquals("bootstrap", apiKey.createdBy());
            assertEquals("platform-team", apiKey.teamId());
            assertNull(apiKey.expiresAt());
        }

        @Test
        @DisplayName("should deserialize 10-field format with empty teamId as null")
        void shouldDeserializeEmptyTeamIdAsNull() {
            var serialized = String.join(
                    "\u0000", "id-4", "hash-4", "key", "", "", "test", "2025-01-01T00:00:00Z", "", "false", "");

            var apiKey = decryptPlaintext(serialized);

            assertNull(apiKey.teamId());
        }
    }
}
