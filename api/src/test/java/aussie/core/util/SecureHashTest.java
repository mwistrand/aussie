package aussie.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SecureHash")
class SecureHashTest {

    @Nested
    @DisplayName("truncatedSha256")
    class TruncatedSha256Tests {

        @Test
        @DisplayName("should produce deterministic output for same input")
        void shouldProduceDeterministicOutput() {
            final var input = "test-token-12345";

            final var hash1 = SecureHash.truncatedSha256(input, 16);
            final var hash2 = SecureHash.truncatedSha256(input, 16);

            assertEquals(hash1, hash2);
        }

        @Test
        @DisplayName("should produce different output for different inputs")
        void shouldProduceDifferentOutputForDifferentInputs() {
            final var hash1 = SecureHash.truncatedSha256("token-a", 16);
            final var hash2 = SecureHash.truncatedSha256("token-b", 16);

            assertNotEquals(hash1, hash2);
        }

        @Test
        @DisplayName("should truncate to specified length")
        void shouldTruncateToSpecifiedLength() {
            final var input = "test-input";

            assertEquals(8, SecureHash.truncatedSha256(input, 8).length());
            assertEquals(16, SecureHash.truncatedSha256(input, 16).length());
            assertEquals(32, SecureHash.truncatedSha256(input, 32).length());
            assertEquals(64, SecureHash.truncatedSha256(input, 64).length());
        }

        @Test
        @DisplayName("should reject hexChars greater than 64")
        void shouldRejectHexCharsGreaterThan64() {
            assertThrows(IllegalArgumentException.class, () -> SecureHash.truncatedSha256("test-input", 65));
        }

        @Test
        @DisplayName("should reject hexChars less than 1")
        void shouldRejectHexCharsLessThan1() {
            assertThrows(IllegalArgumentException.class, () -> SecureHash.truncatedSha256("test-input", 0));
            assertThrows(IllegalArgumentException.class, () -> SecureHash.truncatedSha256("test-input", -1));
        }

        @Test
        @DisplayName("should produce valid hex string")
        void shouldProduceValidHexString() {
            final var hash = SecureHash.truncatedSha256("test-input", 16);

            assertTrue(hash.matches("^[0-9a-f]+$"), "Hash should contain only hex characters");
        }

        @Test
        @DisplayName("should handle empty string")
        void shouldHandleEmptyString() {
            final var hash = SecureHash.truncatedSha256("", 16);

            assertEquals(16, hash.length());
            assertTrue(hash.matches("^[0-9a-f]+$"));
        }

        @Test
        @DisplayName("should handle unicode characters")
        void shouldHandleUnicodeCharacters() {
            final var hash = SecureHash.truncatedSha256("test-\u00e9\u00e8\u00ea", 16);

            assertEquals(16, hash.length());
            assertTrue(hash.matches("^[0-9a-f]+$"));
        }

        @Test
        @DisplayName("should produce known hash for known input")
        void shouldProduceKnownHashForKnownInput() {
            // SHA-256 of "hello" is known: 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
            final var hash = SecureHash.truncatedSha256("hello", 16);

            assertEquals("2cf24dba5fb0a30e", hash);
        }
    }
}
