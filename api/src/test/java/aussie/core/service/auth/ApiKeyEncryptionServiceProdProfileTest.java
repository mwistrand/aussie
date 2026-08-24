package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ApiKeyEncryptionService prod-profile fail-fast")
class ApiKeyEncryptionServiceProdProfileTest {

    private static final String VALID_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Nested
    @DisplayName("startup")
    class Startup {

        @Test
        @DisplayName("refuses to start under prod profile when no key is configured")
        void prodWithoutKeyAborts() {
            var ex = assertThrows(
                    IllegalStateException.class,
                    () -> new ApiKeyEncryptionService(Optional.empty(), "v1", "prod", false));

            assertTrue(
                    ex.getMessage().contains("aussie.auth.encryption.key"),
                    "error must call out the missing config key, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("refuses to start under prod profile when the key is blank")
        void prodWithBlankKeyAborts() {
            assertThrows(
                    IllegalStateException.class,
                    () -> new ApiKeyEncryptionService(Optional.of("   "), "v1", "prod", false));
        }

        @Test
        @DisplayName("treats a profile list that includes 'prod' as production")
        void csvProfileIncludingProdAborts() {
            assertThrows(
                    IllegalStateException.class,
                    () -> new ApiKeyEncryptionService(Optional.empty(), "v1", "secure,prod", false));
        }

        @Test
        @DisplayName("starts in dev with no key (encryption disabled)")
        void devWithoutKeyStarts() {
            assertDoesNotThrow(() -> new ApiKeyEncryptionService(Optional.empty(), "v1", "dev", true));
        }

        @Test
        @DisplayName("starts in prod when a valid key is configured")
        void prodWithKeyStarts() {
            assertDoesNotThrow(() -> new ApiKeyEncryptionService(Optional.of(VALID_KEY), "v1", "prod", false));
        }
    }

    @Nested
    @DisplayName("PLAIN: read gating")
    class PlaintextReadGating {

        @Test
        @DisplayName("refuses to decrypt PLAIN: records when encryption is enabled and flag=false")
        void refusesPlaintextWhenEncryptedAndFlagDisabled() {
            var service = new ApiKeyEncryptionService(Optional.of(VALID_KEY), "v1", "prod", false);
            var ex = assertThrows(IllegalStateException.class, () -> service.decrypt("PLAIN:not-real"));
            assertTrue(
                    ex.getMessage().contains("allow-plaintext-reads"),
                    "error must reference the migration flag, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("decrypts PLAIN: records when encryption is enabled and flag=true")
        void decryptsPlaintextWhenEncryptedAndFlagEnabled() {
            var service = new ApiKeyEncryptionService(Optional.of(VALID_KEY), "v1", "prod", true);
            // We don't decode a real record here — the round-trip is covered in
            // ApiKeyEncryptionServiceTest. We just need to confirm the gate lets us through
            // far enough to fail later (Base64 decode of garbage), not at the gate itself.
            var ex = assertThrows(RuntimeException.class, () -> service.decrypt("PLAIN:!!!"));
            assertFalse(
                    ex.getMessage().contains("allow-plaintext-reads"),
                    "decryption should fail past the gate when the flag is enabled");
        }

        @Test
        @DisplayName("refuses PLAIN: records after the migration deadline")
        void refusesPlaintextAfterMigrationDeadline() {
            var service =
                    new ApiKeyEncryptionService(Optional.of(VALID_KEY), "v1", "prod", true, Optional.of(Instant.EPOCH));

            var ex = assertThrows(IllegalStateException.class, () -> service.decrypt("PLAIN:not-real"));

            assertTrue(ex.getMessage().contains("plaintext-reads-expires-at"));
        }

        @Test
        @DisplayName("permits PLAIN: reads when encryption is disabled, even if flag=false")
        void permitsPlaintextWhenEncryptionDisabled() {
            // When encryption is off, the service writes PLAIN: records, so it must also be
            // able to read them back regardless of the migration flag.
            var service = new ApiKeyEncryptionService(Optional.empty(), "v1", "dev", false);
            var ex = assertThrows(RuntimeException.class, () -> service.decrypt("PLAIN:!!!"));
            assertFalse(
                    ex.getMessage().contains("allow-plaintext-reads"),
                    "gate must be inactive when encryption is disabled, got: " + ex.getMessage());
        }
    }
}
