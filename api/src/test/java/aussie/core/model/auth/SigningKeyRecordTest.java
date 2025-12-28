package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SigningKeyRecord")
class SigningKeyRecordTest {

    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;
    private static String privateKeyPem;
    private static String privateKeyBase64;

    @BeforeAll
    static void generateTestKeys() throws Exception {
        final var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        final var keyPair = keyGen.generateKeyPair();
        privateKey = (RSAPrivateKey) keyPair.getPrivate();
        publicKey = (RSAPublicKey) keyPair.getPublic();

        // Export private key to PEM format for parsing tests
        privateKeyBase64 = Base64.getEncoder().encodeToString(privateKey.getEncoded());
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n" + privateKeyBase64 + "\n-----END PRIVATE KEY-----";
    }

    @Nested
    @DisplayName("factory methods")
    class FactoryMethods {

        @Test
        @DisplayName("pending() should create a PENDING key")
        void pendingShouldCreatePendingKey() {
            final var key = SigningKeyRecord.pending("k-test", privateKey, publicKey);

            assertEquals("k-test", key.keyId());
            assertEquals(KeyStatus.PENDING, key.status());
            assertNotNull(key.createdAt());
            assertNull(key.activatedAt());
            assertNull(key.deprecatedAt());
            assertNull(key.retiredAt());
            assertFalse(key.canSign());
            assertFalse(key.canVerify());
        }

        @Test
        @DisplayName("active() should create an ACTIVE key")
        void activeShouldCreateActiveKey() {
            final var key = SigningKeyRecord.active("k-test", privateKey, publicKey);

            assertEquals("k-test", key.keyId());
            assertEquals(KeyStatus.ACTIVE, key.status());
            assertNotNull(key.createdAt());
            assertNotNull(key.activatedAt());
            assertNull(key.deprecatedAt());
            assertNull(key.retiredAt());
            assertTrue(key.canSign());
            assertTrue(key.canVerify());
        }
    }

    @Nested
    @DisplayName("state transitions")
    class StateTransitions {

        @Test
        @DisplayName("activate() should transition from PENDING to ACTIVE")
        void activateShouldTransitionFromPendingToActive() {
            final var pending = SigningKeyRecord.pending("k-test", privateKey, publicKey);
            final var activatedAt = Instant.now();
            final var active = pending.activate(activatedAt);

            assertEquals(KeyStatus.ACTIVE, active.status());
            assertEquals(activatedAt, active.activatedAt());
            assertTrue(active.canSign());
            assertTrue(active.canVerify());
        }

        @Test
        @DisplayName("activate() should throw when not PENDING")
        void activateShouldThrowWhenNotPending() {
            final var active = SigningKeyRecord.active("k-test", privateKey, publicKey);

            assertThrows(IllegalStateException.class, () -> active.activate(null));
        }

        @Test
        @DisplayName("deprecate() should transition from ACTIVE to DEPRECATED")
        void deprecateShouldTransitionFromActiveToDeprecated() {
            final var active = SigningKeyRecord.active("k-test", privateKey, publicKey);
            final var deprecated = active.deprecate(Instant.now());

            assertEquals(KeyStatus.DEPRECATED, deprecated.status());
            assertNotNull(deprecated.deprecatedAt());
            assertFalse(deprecated.canSign());
            assertTrue(deprecated.canVerify());
        }

        @Test
        @DisplayName("deprecate() should throw when not ACTIVE")
        void deprecateShouldThrowWhenNotActive() {
            final var pending = SigningKeyRecord.pending("k-test", privateKey, publicKey);

            assertThrows(IllegalStateException.class, () -> pending.deprecate(Instant.now()));
        }

        @Test
        @DisplayName("retire() should transition from DEPRECATED to RETIRED")
        void retireShouldTransitionFromDeprecatedToRetired() {
            final var deprecated =
                    SigningKeyRecord.active("k-test", privateKey, publicKey).deprecate(Instant.now());
            final var retired = deprecated.retire(Instant.now());

            assertEquals(KeyStatus.RETIRED, retired.status());
            assertNotNull(retired.retiredAt());
            assertFalse(retired.canSign());
            assertFalse(retired.canVerify());
        }

        @Test
        @DisplayName("retire() should transition from ACTIVE to RETIRED")
        void retireShouldTransitionFromActiveToRetired() {
            final var active = SigningKeyRecord.active("k-test", privateKey, publicKey);
            final var retired = active.retire(Instant.now());

            assertEquals(KeyStatus.RETIRED, retired.status());
            assertNotNull(retired.retiredAt());
        }

        @Test
        @DisplayName("retire() should throw when PENDING")
        void retireShouldThrowWhenPending() {
            final var pending = SigningKeyRecord.pending("k-test", privateKey, publicKey);

            assertThrows(IllegalStateException.class, () -> pending.retire(Instant.now()));
        }
    }

    @Nested
    @DisplayName("withoutPrivateKey()")
    class WithoutPrivateKey {

        @Test
        @DisplayName("should create a copy without private key")
        void shouldCreateCopyWithoutPrivateKey() {
            final var original = SigningKeyRecord.active("k-test", privateKey, publicKey);
            final var withoutPrivate = original.withoutPrivateKey();

            assertEquals(original.keyId(), withoutPrivate.keyId());
            assertEquals(original.publicKey(), withoutPrivate.publicKey());
            assertEquals(original.status(), withoutPrivate.status());
            assertNull(withoutPrivate.privateKey());
            assertFalse(withoutPrivate.canSign());
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("should require keyId")
        void shouldRequireKeyId() {
            assertThrows(NullPointerException.class, () -> SigningKeyRecord.active(null, privateKey, publicKey));
        }

        @Test
        @DisplayName("should require publicKey")
        void shouldRequirePublicKey() {
            assertThrows(NullPointerException.class, () -> SigningKeyRecord.active("k-test", privateKey, null));
        }
    }

    @Nested
    @DisplayName("parsePrivateKey()")
    class ParsePrivateKey {

        @Test
        @DisplayName("should parse PEM-formatted private key")
        void shouldParsePemFormattedKey() {
            final var parsed = SigningKeyRecord.parsePrivateKey(privateKeyPem);

            assertNotNull(parsed);
            assertEquals(privateKey.getModulus(), parsed.getModulus());
            assertEquals(privateKey.getPrivateExponent(), parsed.getPrivateExponent());
        }

        @Test
        @DisplayName("should parse raw base64-encoded private key")
        void shouldParseRawBase64Key() {
            final var parsed = SigningKeyRecord.parsePrivateKey(privateKeyBase64);

            assertNotNull(parsed);
            assertEquals(privateKey.getModulus(), parsed.getModulus());
            assertEquals(privateKey.getPrivateExponent(), parsed.getPrivateExponent());
        }

        @Test
        @DisplayName("should parse PEM with RSA header format")
        void shouldParsePemWithRsaHeader() {
            final var rsaPem =
                    "-----BEGIN RSA PRIVATE KEY-----\n" + privateKeyBase64 + "\n-----END RSA PRIVATE KEY-----";

            // Note: This may fail if the key is in PKCS#8 format (which it is)
            // but the method should still handle it by stripping the headers
            final var parsed = SigningKeyRecord.parsePrivateKey(rsaPem);

            assertNotNull(parsed);
            assertEquals(privateKey.getModulus(), parsed.getModulus());
        }

        @Test
        @DisplayName("should parse PEM with whitespace")
        void shouldParsePemWithWhitespace() {
            final var pemWithWhitespace = "-----BEGIN PRIVATE KEY-----\n"
                    + privateKeyBase64.substring(0, 64) + "\n"
                    + privateKeyBase64.substring(64) + "\n"
                    + "-----END PRIVATE KEY-----";

            final var parsed = SigningKeyRecord.parsePrivateKey(pemWithWhitespace);

            assertNotNull(parsed);
            assertEquals(privateKey.getModulus(), parsed.getModulus());
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for invalid key data")
        void shouldThrowForInvalidKeyData() {
            assertThrows(IllegalArgumentException.class, () -> SigningKeyRecord.parsePrivateKey("not-a-valid-key"));
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for empty string")
        void shouldThrowForEmptyString() {
            assertThrows(IllegalArgumentException.class, () -> SigningKeyRecord.parsePrivateKey(""));
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for malformed base64")
        void shouldThrowForMalformedBase64() {
            assertThrows(
                    IllegalArgumentException.class, () -> SigningKeyRecord.parsePrivateKey("!!!invalid-base64!!!"));
        }
    }

    @Nested
    @DisplayName("derivePublicKey()")
    class DerivePublicKey {

        @Test
        @DisplayName("should derive matching public key from private key")
        void shouldDeriveMatchingPublicKey() {
            final var derived = SigningKeyRecord.derivePublicKey(privateKey);

            assertNotNull(derived);
            assertEquals(publicKey.getModulus(), derived.getModulus());
            assertEquals(publicKey.getPublicExponent(), derived.getPublicExponent());
        }

        @Test
        @DisplayName("should derive public key from parsed private key")
        void shouldDerivePublicKeyFromParsedKey() {
            final var parsed = SigningKeyRecord.parsePrivateKey(privateKeyPem);
            final var derived = SigningKeyRecord.derivePublicKey(parsed);

            assertNotNull(derived);
            assertEquals(publicKey.getModulus(), derived.getModulus());
            assertEquals(publicKey.getPublicExponent(), derived.getPublicExponent());
        }
    }
}
