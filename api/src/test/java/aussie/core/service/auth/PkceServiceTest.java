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
import java.time.Instant;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.PkceConfig;
import aussie.core.model.auth.OidcAuthorizationTransaction;
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
    @DisplayName("PKCE input validation")
    class PkceInputValidationTests {

        @Test
        void acceptsRfc7636Values() {
            final var verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
            final var challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

            assertTrue(pkceService.isValidCodeVerifier(verifier));
            assertTrue(pkceService.isValidCodeChallenge(challenge));
            assertTrue(pkceService.isValidState(challenge));
        }

        @Test
        void rejectsValuesOutsideGrammarOrLengthLimits() {
            assertFalse(pkceService.isValidCodeVerifier("too-short"));
            assertFalse(pkceService.isValidCodeVerifier("a".repeat(42) + "!"));
            assertFalse(pkceService.isValidCodeVerifier("a".repeat(129)));
            assertFalse(pkceService.isValidCodeChallenge("a".repeat(42)));
            assertFalse(pkceService.isValidCodeChallenge("a".repeat(42) + "="));
            assertFalse(pkceService.isValidState("a".repeat(44)));
        }
    }

    @Nested
    @DisplayName("transaction storage")
    class StoreChallengeTests {

        @Test
        @DisplayName("should store challenge with configured TTL")
        void shouldStoreChallengeWithConfiguredTtl() {
            String state = "test-state";
            final var transaction = transaction("test-challenge");

            when(repository.store(anyString(), any(OidcAuthorizationTransaction.class), any(Duration.class)))
                    .thenReturn(Uni.createFrom().voidItem());

            pkceService.storeTransaction(state, transaction).await().atMost(Duration.ofSeconds(1));

            verify(repository).store(eq(state), eq(transaction), eq(Duration.ofMinutes(10)));
        }

        @Test
        @DisplayName("should reject null state")
        void shouldRejectNullState() {
            assertThrows(IllegalArgumentException.class, () -> pkceService.storeTransaction(null, transaction("x")));
        }

        @Test
        @DisplayName("should reject blank state")
        void shouldRejectBlankState() {
            assertThrows(IllegalArgumentException.class, () -> pkceService.storeTransaction("  ", transaction("x")));
        }

        @Test
        @DisplayName("should reject null transaction")
        void shouldRejectNullTransaction() {
            assertThrows(IllegalArgumentException.class, () -> pkceService.storeTransaction("state", null));
        }
    }

    @Nested
    @DisplayName("verifyChallenge()")
    class VerifyChallengeTests {

        @Test
        @DisplayName("should return true for valid verifier")
        void shouldReturnTrueForValidVerifier() {
            String verifier = pkceService.generateCodeVerifier();
            String challenge = pkceService.generateChallenge(verifier);

            boolean result = pkceService.verifyChallenge(transaction(challenge), verifier);

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false for invalid verifier")
        void shouldReturnFalseForInvalidVerifier() {
            String challenge = "stored-challenge";
            String wrongVerifier = "wrong-verifier-with-sufficient-length-123456";

            boolean result = pkceService.verifyChallenge(transaction(challenge), wrongVerifier);

            assertFalse(result);
        }

        @Test
        @DisplayName("should consume transaction by state")
        void shouldConsumeTransaction() {
            String state = "test-state";
            final var transaction = transaction("challenge");

            when(repository.consume(state)).thenReturn(Uni.createFrom().item(Optional.of(transaction)));

            final var result = pkceService.consumeTransaction(state).await().atMost(Duration.ofSeconds(1));

            assertEquals(Optional.of(transaction), result);
            verify(repository).consume(state);
        }

        @Test
        @DisplayName("should reject null state")
        void shouldRejectNullState() {
            assertThrows(IllegalArgumentException.class, () -> pkceService.consumeTransaction(null));
            verify(repository, never()).consume(anyString());
        }

        @Test
        @DisplayName("should reject blank state")
        void shouldRejectBlankState() {
            assertThrows(IllegalArgumentException.class, () -> pkceService.consumeTransaction("  "));
            verify(repository, never()).consume(anyString());
        }

        @Test
        @DisplayName("should reject null verifier")
        void shouldRejectNullVerifier() {
            assertFalse(pkceService.verifyChallenge(transaction("challenge"), null));
        }

        @Test
        @DisplayName("should reject blank verifier")
        void shouldRejectBlankVerifier() {
            assertFalse(pkceService.verifyChallenge(transaction("challenge"), "  "));
        }
    }

    private OidcAuthorizationTransaction transaction(String challenge) {
        final var now = Instant.now();
        return new OidcAuthorizationTransaction(
                "provider",
                "https://app.example/callback",
                challenge,
                "nonce",
                OidcAuthorizationTransaction.ClientType.PUBLIC,
                now,
                now.plusSeconds(600));
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
}
