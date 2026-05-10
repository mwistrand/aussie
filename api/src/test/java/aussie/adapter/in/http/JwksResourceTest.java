package aussie.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.KeyRotationConfig;
import aussie.core.model.auth.KeyStatus;
import aussie.core.model.auth.SigningKeyRecord;
import aussie.core.service.auth.SigningKeyRegistry;

@DisplayName("JwksResource")
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class JwksResourceTest {

    private static RSAPublicKey publicKey;
    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey2;
    private static RSAPrivateKey privateKey2;

    @Mock
    private SigningKeyRegistry keyRegistry;

    @Mock
    private KeyRotationConfig keyRotationConfig;

    private JwksResource resource;

    @BeforeAll
    static void generateKeys() throws Exception {
        var keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);

        var keyPair1 = keyGen.generateKeyPair();
        publicKey = (RSAPublicKey) keyPair1.getPublic();
        privateKey = (RSAPrivateKey) keyPair1.getPrivate();

        var keyPair2 = keyGen.generateKeyPair();
        publicKey2 = (RSAPublicKey) keyPair2.getPublic();
        privateKey2 = (RSAPrivateKey) keyPair2.getPrivate();
    }

    @BeforeEach
    void setUp() {
        resource = new JwksResource(keyRegistry, keyRotationConfig);
    }

    @Nested
    @DisplayName("getJwks")
    class GetJwks {

        @Test
        @DisplayName("returns empty keys when key rotation is disabled")
        void returnsEmptyKeysWhenDisabled() {
            when(keyRotationConfig.enabled()).thenReturn(false);

            final var response = resource.getJwks();

            assertEquals(200, response.getStatus());
            final var body = (Map<String, Object>) response.getEntity();
            assertNotNull(body.get("keys"));
            final var keys = (List<?>) body.get("keys");
            assertTrue(keys.isEmpty());
            assertNull(response.getHeaderString("Cache-Control"));
        }

        @Test
        @DisplayName("returns empty keys when enabled but no verification keys exist")
        void returnsEmptyKeysWhenNoVerificationKeys() {
            when(keyRotationConfig.enabled()).thenReturn(true);
            when(keyRegistry.getVerificationKeys()).thenReturn(List.of());

            final var response = resource.getJwks();

            assertEquals(200, response.getStatus());
            final var body = (Map<String, Object>) response.getEntity();
            final var keys = (List<?>) body.get("keys");
            assertTrue(keys.isEmpty());
            assertNull(response.getHeaderString("Cache-Control"));
        }

        @Test
        @DisplayName("returns JWK entries with Cache-Control header when keys are present")
        void returnsJwkEntriesWithCacheControl() {
            when(keyRotationConfig.enabled()).thenReturn(true);

            final var now = Instant.now();
            final var key1 =
                    new SigningKeyRecord("kid-1", privateKey, publicKey, KeyStatus.ACTIVE, now, now, null, null);
            final var key2 =
                    new SigningKeyRecord("kid-2", privateKey2, publicKey2, KeyStatus.DEPRECATED, now, now, now, null);

            when(keyRegistry.getVerificationKeys()).thenReturn(List.of(key1, key2));

            final var response = resource.getJwks();

            assertEquals(200, response.getStatus());
            assertEquals("public, max-age=3600", response.getHeaderString("Cache-Control"));

            final var body = (Map<String, Object>) response.getEntity();
            final var keys = (List<Map<String, Object>>) body.get("keys");
            assertEquals(2, keys.size());
        }
    }

    @Nested
    @DisplayName("toJwk")
    class ToJwk {

        @Test
        @DisplayName("produces correct RSA JWK fields")
        void producesCorrectJwkFields() {
            when(keyRotationConfig.enabled()).thenReturn(true);

            final var now = Instant.now();
            final var keyRecord =
                    new SigningKeyRecord("test-kid", privateKey, publicKey, KeyStatus.ACTIVE, now, now, null, null);
            when(keyRegistry.getVerificationKeys()).thenReturn(List.of(keyRecord));

            final var response = resource.getJwks();

            final var body = (Map<String, Object>) response.getEntity();
            final var keys = (List<Map<String, Object>>) body.get("keys");
            final var jwk = keys.get(0);

            assertEquals("RSA", jwk.get("kty"));
            assertEquals("test-kid", jwk.get("kid"));
            assertEquals("sig", jwk.get("use"));
            assertEquals("RS256", jwk.get("alg"));

            // Verify n and e are valid base64url-encoded values
            final var n = (String) jwk.get("n");
            final var e = (String) jwk.get("e");
            assertNotNull(n);
            assertNotNull(e);

            // Decode and verify they match the original key
            final var decodedN = new BigInteger(1, Base64.getUrlDecoder().decode(n));
            final var decodedE = new BigInteger(1, Base64.getUrlDecoder().decode(e));
            assertEquals(publicKey.getModulus(), decodedN);
            assertEquals(publicKey.getPublicExponent(), decodedE);
        }
    }

    @Nested
    @DisplayName("base64UrlEncode")
    class Base64UrlEncode {

        @Test
        @DisplayName("encodes a normal BigInteger correctly")
        void encodesNormalBigInteger() {
            when(keyRotationConfig.enabled()).thenReturn(true);

            final var now = Instant.now();
            final var keyRecord =
                    new SigningKeyRecord("kid-enc", privateKey, publicKey, KeyStatus.ACTIVE, now, now, null, null);
            when(keyRegistry.getVerificationKeys()).thenReturn(List.of(keyRecord));

            final var response = resource.getJwks();

            final var body = (Map<String, Object>) response.getEntity();
            final var keys = (List<Map<String, Object>>) body.get("keys");
            final var jwk = keys.get(0);

            // The public exponent (e) is typically 65537, which has no leading zero byte
            final var e = (String) jwk.get("e");
            assertNotNull(e);
            // Verify it decodes without error and matches
            final var decoded = Base64.getUrlDecoder().decode(e);
            final var value = new BigInteger(1, decoded);
            assertEquals(publicKey.getPublicExponent(), value);
        }

        @Test
        @DisplayName("strips leading zero byte from two's complement encoding")
        void stripsLeadingZeroByte() {
            when(keyRotationConfig.enabled()).thenReturn(true);

            final var now = Instant.now();
            final var keyRecord =
                    new SigningKeyRecord("kid-zero", privateKey, publicKey, KeyStatus.ACTIVE, now, now, null, null);
            when(keyRegistry.getVerificationKeys()).thenReturn(List.of(keyRecord));

            final var response = resource.getJwks();

            final var body = (Map<String, Object>) response.getEntity();
            final var keys = (List<Map<String, Object>>) body.get("keys");
            final var jwk = keys.get(0);

            // The modulus (n) for a 2048-bit RSA key has a high bit set,
            // so BigInteger.toByteArray() will include a leading zero byte.
            // The encoding should strip it.
            final var n = (String) jwk.get("n");
            final var decodedBytes = Base64.getUrlDecoder().decode(n);

            // For a 2048-bit key, the modulus should be 256 bytes (not 257 with leading zero)
            assertTrue(decodedBytes.length <= 256, "Modulus should be at most 256 bytes after stripping leading zero");

            // Verify round-trip correctness
            final var decodedN = new BigInteger(1, decodedBytes);
            assertEquals(publicKey.getModulus(), decodedN);
        }
    }
}
