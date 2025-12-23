package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.PkceConfig;
import aussie.core.port.out.PkceChallengeRepository;

@DisplayName("PkceService")
class PkceServiceTest {

    private PkceService pkceService;
    private PkceChallengeRepository repository;
    private PkceConfig config;

    @BeforeEach
    void setUp() {
        repository = mock(PkceChallengeRepository.class);
        config = mock(PkceConfig.class);

        when(config.enabled()).thenReturn(true);
        when(config.required()).thenReturn(true);
        when(config.challengeTtl()).thenReturn(Duration.ofMinutes(10));

        pkceService = new PkceService(repository, config);
    }

    @Nested
    @DisplayName("generateCodeVerifier()")
    class GenerateCodeVerifierTests {

        @Test
        @DisplayName("should generate verifier with at least 43 characters")
        void shouldGenerateVerifierWithMinimumLength() {
            String verifier = pkceService.generateCodeVerifier();

            assertNotNull(verifier);
            assertTrue(verifier.length() >= 43, "Verifier must be at least 43 characters per RFC 7636");
        }

        @Test
        @DisplayName("should generate verifier with at most 128 characters")
        void shouldGenerateVerifierWithMaximumLength() {
            String verifier = pkceService.generateCodeVerifier();

            assertTrue(verifier.length() <= 128, "Verifier must be at most 128 characters per RFC 7636");
        }

        @Test
        @DisplayName("should generate URL-safe characters only")
        void shouldGenerateUrlSafeCharacters() {
            String verifier = pkceService.generateCodeVerifier();

            // URL-safe Base64 uses A-Z, a-z, 0-9, -, _
            assertTrue(verifier.matches("^[A-Za-z0-9_-]+$"), "Verifier must contain only URL-safe characters");
        }

        @Test
        @DisplayName("should generate unique verifiers")
        void shouldGenerateUniqueVerifiers() {
            String verifier1 = pkceService.generateCodeVerifier();
            String verifier2 = pkceService.generateCodeVerifier();

            assertNotEquals(verifier1, verifier2, "Each verifier should be unique");
        }
    }

    @Nested
    @DisplayName("generateChallenge()")
    class GenerateChallengeTests {

        @Test
        @DisplayName("should produce valid S256 challenge")
        void shouldProduceValidS256Challenge() {
            String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
            String expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

            String challenge = pkceService.generateChallenge(verifier);

            assertEquals(expectedChallenge, challenge);
        }

        @Test
        @DisplayName("should produce consistent challenge for same verifier")
        void shouldProduceConsistentChallenge() {
            String verifier = pkceService.generateCodeVerifier();

            String challenge1 = pkceService.generateChallenge(verifier);
            String challenge2 = pkceService.generateChallenge(verifier);

            assertEquals(challenge1, challenge2);
        }

        @Test
        @DisplayName("should produce different challenges for different verifiers")
        void shouldProduceDifferentChallenges() {
            String verifier1 = pkceService.generateCodeVerifier();
            String verifier2 = pkceService.generateCodeVerifier();

            String challenge1 = pkceService.generateChallenge(verifier1);
            String challenge2 = pkceService.generateChallenge(verifier2);

            assertNotEquals(challenge1, challenge2);
        }

        @Test
        @DisplayName("should produce URL-safe output")
        void shouldProduceUrlSafeOutput() {
            String verifier = pkceService.generateCodeVerifier();
            String challenge = pkceService.generateChallenge(verifier);

            // URL-safe Base64 without padding
            assertTrue(challenge.matches("^[A-Za-z0-9_-]+$"), "Challenge must be URL-safe Base64");
            assertFalse(challenge.contains("="), "Challenge should not contain padding");
        }
    }

    @Nested
    @DisplayName("isValidChallengeMethod()")
    class IsValidChallengeMethodTests {

        @Test
        @DisplayName("should accept S256 method")
        void shouldAcceptS256() {
            assertTrue(pkceService.isValidChallengeMethod("S256"));
        }

        @Test
        @DisplayName("should reject plain method")
        void shouldRejectPlain() {
            assertFalse(pkceService.isValidChallengeMethod("plain"));
        }

        @Test
        @DisplayName("should reject null method")
        void shouldRejectNull() {
            assertFalse(pkceService.isValidChallengeMethod(null));
        }

        @Test
        @DisplayName("should reject empty method")
        void shouldRejectEmpty() {
            assertFalse(pkceService.isValidChallengeMethod(""));
        }

        @Test
        @DisplayName("should be case-sensitive")
        void shouldBeCaseSensitive() {
            assertFalse(pkceService.isValidChallengeMethod("s256"));
            assertFalse(pkceService.isValidChallengeMethod("S256 "));
        }
    }

    @Nested
    @DisplayName("storeChallenge()")
    class StoreChallengeTests {

        @Test
        @DisplayName("should store challenge with configured TTL")
        void shouldStoreChallengeWithConfiguredTtl() {
            String state = "test-state";
            String challenge = "test-challenge";

            when(repository.store(anyString(), anyString(), any(Duration.class)))
                    .thenReturn(Uni.createFrom().voidItem());

            pkceService.storeChallenge(state, challenge).await().atMost(Duration.ofSeconds(1));

            verify(repository).store(eq(state), eq(challenge), eq(Duration.ofMinutes(10)));
        }

        @Test
        @DisplayName("should reject null state")
        void shouldRejectNullState() {
            assertThrows(IllegalArgumentException.class, () -> pkceService.storeChallenge(null, "challenge"));
        }

        @Test
        @DisplayName("should reject blank state")
        void shouldRejectBlankState() {
            assertThrows(IllegalArgumentException.class, () -> pkceService.storeChallenge("  ", "challenge"));
        }

        @Test
        @DisplayName("should reject null challenge")
        void shouldRejectNullChallenge() {
            assertThrows(IllegalArgumentException.class, () -> pkceService.storeChallenge("state", null));
        }

        @Test
        @DisplayName("should reject blank challenge")
        void shouldRejectBlankChallenge() {
            assertThrows(IllegalArgumentException.class, () -> pkceService.storeChallenge("state", "  "));
        }
    }

    @Nested
    @DisplayName("verifyChallenge()")
    class VerifyChallengeTests {

        @Test
        @DisplayName("should return true for valid verifier")
        void shouldReturnTrueForValidVerifier() {
            String state = "test-state";
            String verifier = pkceService.generateCodeVerifier();
            String challenge = pkceService.generateChallenge(verifier);

            when(repository.consumeChallenge(state)).thenReturn(Uni.createFrom().item(Optional.of(challenge)));

            Boolean result =
                    pkceService.verifyChallenge(state, verifier).await().atMost(Duration.ofSeconds(1));

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false for invalid verifier")
        void shouldReturnFalseForInvalidVerifier() {
            String state = "test-state";
            String challenge = "stored-challenge";
            String wrongVerifier = "wrong-verifier-with-sufficient-length-123456";

            when(repository.consumeChallenge(state)).thenReturn(Uni.createFrom().item(Optional.of(challenge)));

            Boolean result =
                    pkceService.verifyChallenge(state, wrongVerifier).await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false when challenge not found")
        void shouldReturnFalseWhenChallengeNotFound() {
            String state = "unknown-state";
            String anyVerifier = "some-verifier-with-sufficient-length-12345";

            when(repository.consumeChallenge(state)).thenReturn(Uni.createFrom().item(Optional.empty()));

            Boolean result =
                    pkceService.verifyChallenge(state, anyVerifier).await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }

        @Test
        @DisplayName("should consume challenge (one-time use)")
        void shouldConsumeChallenge() {
            String state = "test-state";
            String verifier = pkceService.generateCodeVerifier();
            String challenge = pkceService.generateChallenge(verifier);

            when(repository.consumeChallenge(state)).thenReturn(Uni.createFrom().item(Optional.of(challenge)));

            pkceService.verifyChallenge(state, verifier).await().atMost(Duration.ofSeconds(1));

            verify(repository).consumeChallenge(state);
        }

        @Test
        @DisplayName("should reject null state")
        void shouldRejectNullState() {
            assertThrows(IllegalArgumentException.class, () -> pkceService.verifyChallenge(null, "verifier"));
            verify(repository, never()).consumeChallenge(anyString());
        }

        @Test
        @DisplayName("should reject blank state")
        void shouldRejectBlankState() {
            assertThrows(IllegalArgumentException.class, () -> pkceService.verifyChallenge("  ", "verifier"));
            verify(repository, never()).consumeChallenge(anyString());
        }

        @Test
        @DisplayName("should reject null verifier")
        void shouldRejectNullVerifier() {
            assertThrows(IllegalArgumentException.class, () -> pkceService.verifyChallenge("state", null));
            verify(repository, never()).consumeChallenge(anyString());
        }

        @Test
        @DisplayName("should reject blank verifier")
        void shouldRejectBlankVerifier() {
            assertThrows(IllegalArgumentException.class, () -> pkceService.verifyChallenge("state", "  "));
            verify(repository, never()).consumeChallenge(anyString());
        }
    }

    @Nested
    @DisplayName("isEnabled()")
    class IsEnabledTests {

        @Test
        @DisplayName("should return config value")
        void shouldReturnConfigValue() {
            when(config.enabled()).thenReturn(true);
            assertTrue(pkceService.isEnabled());

            when(config.enabled()).thenReturn(false);
            assertFalse(pkceService.isEnabled());
        }
    }

    @Nested
    @DisplayName("isRequired()")
    class IsRequiredTests {

        @Test
        @DisplayName("should return config value")
        void shouldReturnConfigValue() {
            when(config.required()).thenReturn(true);
            assertTrue(pkceService.isRequired());

            when(config.required()).thenReturn(false);
            assertFalse(pkceService.isRequired());
        }
    }
}
