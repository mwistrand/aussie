package aussie.adapter.out.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.smallrye.mutiny.Uni;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.NumericDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.ResiliencyConfig;
import aussie.core.model.auth.TokenProviderConfig;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.port.out.JwksCache;

@DisplayName("OidcTokenValidator")
@ExtendWith(MockitoExtension.class)
class OidcTokenValidatorTest {

    private static final String TEST_ISSUER = "https://auth.example.com";
    private static final String TEST_SUBJECT = "user-123";
    private static final String TEST_KEY_ID = "test-key-1";
    private static final String TEST_AUDIENCE = "test-audience";
    private static final URI TEST_JWKS_URI = URI.create("https://auth.example.com/.well-known/jwks.json");

    private static KeyPair keyPair;
    private static RsaJsonWebKey rsaJwk;

    @Mock
    private JwksCache jwksCache;

    @Mock
    private ResiliencyConfig resiliencyConfig;

    @Mock
    private ResiliencyConfig.JwksConfig jwksConfig;

    private OidcTokenValidator validator;
    private TokenProviderConfig config;

    @BeforeAll
    static void setUpKeys() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        keyPair = keyGen.generateKeyPair();

        rsaJwk = new RsaJsonWebKey((RSAPublicKey) keyPair.getPublic());
        rsaJwk.setKeyId(TEST_KEY_ID);
        rsaJwk.setAlgorithm(AlgorithmIdentifiers.RSA_USING_SHA256);
        rsaJwk.setUse("sig");
    }

    @BeforeEach
    void setUp() {
        when(resiliencyConfig.jwks()).thenReturn(jwksConfig);
        when(jwksConfig.cacheTtl()).thenReturn(Duration.ofHours(1));
        validator = new OidcTokenValidator(jwksCache, resiliencyConfig);
        config = TokenProviderConfig.builder("test-provider", TEST_ISSUER, TEST_JWKS_URI)
                .audiences(Set.of(TEST_AUDIENCE))
                .build();
    }

    @Test
    @DisplayName("name() should return 'oidc'")
    void nameShouldReturnOidc() {
        assertEquals("oidc", validator.name());
    }

    @Test
    @DisplayName("priority() should return 100")
    void priorityShouldReturn100() {
        assertEquals(100, validator.priority());
    }

    @Nested
    @DisplayName("validate() with null/blank tokens")
    class NullBlankTokenTests {

        @Test
        @DisplayName("should return NoToken for null token")
        void shouldReturnNoTokenForNull() {
            final var result = validator.validate(null, config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.NoToken.class, result);
            verify(jwksCache, never()).getKey(any(), any());
        }

        @Test
        @DisplayName("should return NoToken for blank token")
        void shouldReturnNoTokenForBlank() {
            final var result = validator.validate("   ", config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.NoToken.class, result);
            verify(jwksCache, never()).getKey(any(), any());
        }

        @Test
        @DisplayName("should return NoToken for empty token")
        void shouldReturnNoTokenForEmpty() {
            final var result = validator.validate("", config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.NoToken.class, result);
        }
    }

    @Nested
    @DisplayName("validate() with valid tokens")
    class ValidTokenTests {

        @Test
        @DisplayName("should return Valid for correctly signed token")
        void shouldReturnValidForCorrectlySignedToken() throws Exception {
            final var token = createValidToken();
            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

            final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Valid.class, result);
            final var valid = (TokenValidationResult.Valid) result;
            assertEquals(TEST_SUBJECT, valid.subject());
            assertEquals(TEST_ISSUER, valid.issuer());
            assertEquals("test-provider", valid.identity().providerId());
            assertEquals(Set.of(TEST_AUDIENCE), valid.identity().audiences());
            assertTrue(valid.identity().tokenId().isPresent());
            assertNotNull(valid.expiresAt());
            assertTrue(valid.expiresAt().isAfter(Instant.now()));
        }

        @Test
        @DisplayName("should include all claims in the result")
        void shouldIncludeAllClaimsInResult() throws Exception {
            final var token = createTokenWithClaims(Map.of(
                    "custom_claim", "custom_value",
                    "roles", "admin,user"));
            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

            final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Valid.class, result);
            final var valid = (TokenValidationResult.Valid) result;
            assertEquals("custom_value", valid.claims().get("custom_claim"));
            assertEquals("admin,user", valid.claims().get("roles"));
        }

        @Test
        @DisplayName("should apply claims mapping when configured")
        void shouldApplyClaimsMapping() throws Exception {
            final var mappedConfig = TokenProviderConfig.builder("test-provider", TEST_ISSUER, TEST_JWKS_URI)
                    .audiences(Set.of(TEST_AUDIENCE))
                    .claimsMapping(Map.of("external_id", "internal_id"))
                    .build();
            final var token = createTokenWithClaims(Map.of("external_id", "ext-123"));
            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

            final var result = validator.validate(token, mappedConfig).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Valid.class, result);
            final var valid = (TokenValidationResult.Valid) result;
            assertEquals("ext-123", valid.claims().get("external_id"));
            assertEquals("ext-123", valid.claims().get("internal_id"));
        }
    }

    @Nested
    @DisplayName("validate() with invalid tokens")
    class InvalidTokenTests {

        @Test
        @DisplayName("should return Invalid for expired token")
        void shouldReturnInvalidForExpiredToken() throws Exception {
            final var token = createExpiredToken();
            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

            final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Invalid.class, result);
            final var invalid = (TokenValidationResult.Invalid) result;
            assertEquals("Token has expired", invalid.reason());
        }

        @Test
        @DisplayName("should return Invalid for wrong issuer")
        void shouldReturnInvalidForWrongIssuer() throws Exception {
            final var token = createTokenWithIssuer("https://wrong-issuer.com");
            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

            final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Invalid.class, result);
            final var invalid = (TokenValidationResult.Invalid) result;
            assertEquals("Invalid token issuer", invalid.reason());
        }

        @Test
        @DisplayName("should return Invalid for wrong audience when audiences configured")
        void shouldReturnInvalidForWrongAudience() throws Exception {
            final var audienceConfig = TokenProviderConfig.builder("test-provider", TEST_ISSUER, TEST_JWKS_URI)
                    .audiences(Set.of("expected-audience"))
                    .build();
            final var token = createTokenWithAudience("wrong-audience");
            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

            final var result = validator.validate(token, audienceConfig).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Invalid.class, result);
            final var invalid = (TokenValidationResult.Invalid) result;
            assertEquals("Invalid token audience", invalid.reason());
        }

        @Test
        @DisplayName("should return Valid when audience matches configured audiences")
        void shouldReturnValidWhenAudienceMatches() throws Exception {
            final var audienceConfig = TokenProviderConfig.builder("test-provider", TEST_ISSUER, TEST_JWKS_URI)
                    .audiences(Set.of("expected-audience", "another-audience"))
                    .build();
            final var token = createTokenWithAudience("expected-audience");
            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

            final var result = validator.validate(token, audienceConfig).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("should return Invalid for token signed with wrong key")
        void shouldReturnInvalidForWrongSignature() throws Exception {
            // Create a different key pair
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            final var wrongKeyPair = keyGen.generateKeyPair();
            final var token = createTokenWithKey(wrongKeyPair);

            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

            final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Invalid.class, result);
            final var invalid = (TokenValidationResult.Invalid) result;
            assertEquals("Invalid token signature", invalid.reason());
        }

        @Test
        @DisplayName("should return Invalid for malformed token")
        void shouldReturnInvalidForMalformedToken() {
            final var result =
                    validator.validate("not.a.valid.jwt", config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Invalid.class, result);
        }

        @Test
        @DisplayName("should return Invalid for token without subject")
        void shouldReturnInvalidForTokenWithoutSubject() throws Exception {
            final var token = createTokenWithoutSubject();
            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

            final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Invalid.class, result);
        }
    }

    @Nested
    @DisplayName("validate() with key rotation")
    class KeyRotationTests {

        @Test
        @DisplayName("should refresh JWKS when key not found initially")
        void shouldRefreshJwksWhenKeyNotFound() throws Exception {
            final var token = createValidToken();
            final var keySet = new JsonWebKeySet(rsaJwk);

            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            when(jwksCache.refresh(TEST_JWKS_URI)).thenReturn(Uni.createFrom().item(keySet));

            final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Valid.class, result);
            verify(jwksCache).refresh(TEST_JWKS_URI);
        }

        @Test
        @DisplayName("should return Invalid when key not found even after refresh")
        void shouldReturnInvalidWhenKeyNotFoundAfterRefresh() throws Exception {
            final var token = createValidToken();
            final var emptyKeySet = new JsonWebKeySet();

            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            when(jwksCache.refresh(TEST_JWKS_URI)).thenReturn(Uni.createFrom().item(emptyKeySet));

            final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Invalid.class, result);
            final var invalid = (TokenValidationResult.Invalid) result;
            assertEquals("Signing key not found in JWKS", invalid.reason());
        }

        @Test
        @DisplayName("should find key by ID after refresh")
        void shouldFindKeyByIdAfterRefresh() throws Exception {
            // Create a token with a different key ID
            final var differentKeyId = "rotated-key-id";
            final var token = createTokenWithKeyId(differentKeyId);

            final var rotatedJwk = new RsaJsonWebKey((RSAPublicKey) keyPair.getPublic());
            rotatedJwk.setKeyId(differentKeyId);
            rotatedJwk.setAlgorithm(AlgorithmIdentifiers.RSA_USING_SHA256);
            final var keySet = new JsonWebKeySet(rotatedJwk);

            when(jwksCache.getKey(eq(TEST_JWKS_URI), eq(differentKeyId)))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            when(jwksCache.refresh(TEST_JWKS_URI)).thenReturn(Uni.createFrom().item(keySet));

            final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Valid.class, result);
        }
    }

    @Nested
    @DisplayName("validate() consumer cache + token header parse")
    class ConsumerCacheTests {

        @Test
        @DisplayName("repeated validation reuses the cached JwtConsumer (no behavioral change)")
        void repeatedValidationReturnsConsistentResults() throws Exception {
            final var token = createValidToken();
            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

            final var first = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));
            final var second = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Valid.class, first);
            assertInstanceOf(TokenValidationResult.Valid.class, second);
            assertEquals(
                    ((TokenValidationResult.Valid) first).subject(), ((TokenValidationResult.Valid) second).subject());
            verify(jwksCache, org.mockito.Mockito.times(2)).getKey(TEST_JWKS_URI, TEST_KEY_ID);
        }

        @Test
        @DisplayName("different audiences for same issuer use distinct consumers")
        void differentAudiencesDoNotShareConsumer() throws Exception {
            final var configA = TokenProviderConfig.builder("test-provider", TEST_ISSUER, TEST_JWKS_URI)
                    .audiences(Set.of("aud-a"))
                    .build();
            final var configB = TokenProviderConfig.builder("test-provider", TEST_ISSUER, TEST_JWKS_URI)
                    .audiences(Set.of("aud-b"))
                    .build();
            final var tokenA = createTokenWithAudience("aud-a");
            final var tokenB = createTokenWithAudience("aud-b");
            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

            final var resultA = validator.validate(tokenA, configA).await().atMost(Duration.ofSeconds(1));
            final var resultB = validator.validate(tokenB, configB).await().atMost(Duration.ofSeconds(1));

            // If configs erroneously shared a consumer, one of these would fail audience validation.
            assertInstanceOf(TokenValidationResult.Valid.class, resultA);
            assertInstanceOf(TokenValidationResult.Valid.class, resultB);
        }

        @Test
        @DisplayName("reused key ID cannot retain old verification material")
        void reusedKeyIdUsesPublicKeyThumbprint() throws Exception {
            final var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            final var rotatedPair = generator.generateKeyPair();
            final var rotatedJwk = new RsaJsonWebKey((RSAPublicKey) rotatedPair.getPublic());
            rotatedJwk.setKeyId(TEST_KEY_ID);
            rotatedJwk.setAlgorithm(AlgorithmIdentifiers.RSA_USING_SHA256);
            rotatedJwk.setUse("sig");

            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(
                            Uni.createFrom().item(Optional.of(rsaJwk)),
                            Uni.createFrom().item(Optional.of(rotatedJwk)));

            final var beforeRotation =
                    validator.validate(createValidToken(), config).await().atMost(Duration.ofSeconds(1));
            final var afterRotation = validator
                    .validate(createTokenWithKey(rotatedPair), config)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Valid.class, beforeRotation);
            assertInstanceOf(TokenValidationResult.Valid.class, afterRotation);
        }

        @Test
        @DisplayName("changed JWK algorithm metadata cannot reuse a permissive consumer")
        void changedJwkAlgorithmUsesDistinctConsumer() throws Exception {
            final var algorithms = TokenProviderConfig.builder("test-provider", TEST_ISSUER, TEST_JWKS_URI)
                    .audiences(Set.of(TEST_AUDIENCE))
                    .allowedAlgorithms(Set.of("RS256", "RS512"))
                    .build();
            final var restrictedJwk = new RsaJsonWebKey((RSAPublicKey) keyPair.getPublic());
            restrictedJwk.setKeyId(TEST_KEY_ID);
            restrictedJwk.setAlgorithm(AlgorithmIdentifiers.RSA_USING_SHA512);
            restrictedJwk.setUse("sig");
            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(
                            Uni.createFrom().item(Optional.of(rsaJwk)),
                            Uni.createFrom().item(Optional.of(restrictedJwk)));

            final var beforeRestriction =
                    validator.validate(createValidToken(), algorithms).await().atMost(Duration.ofSeconds(1));
            final var afterRestriction =
                    validator.validate(createValidToken(), algorithms).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Valid.class, beforeRestriction);
            assertInstanceOf(TokenValidationResult.Invalid.class, afterRestriction);
        }

        @Test
        @DisplayName("token with missing kid header still validates against single-key JWKS")
        void tokenWithoutKidIsAccepted() throws Exception {
            final var token = createTokenWithoutKid();
            // jwksCache.getKey is queried with null kid; JwksCacheService returns the lone key.
            when(jwksCache.getKey(TEST_JWKS_URI, null))
                    .thenReturn(Uni.createFrom().item(Optional.of(rsaJwk)));

            final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Valid.class, result);
        }

        @Test
        @DisplayName("token whose first segment is not valid base64url returns Invalid")
        void malformedHeaderReturnsInvalid() {
            final var result =
                    validator.validate("!!!.payload.sig", config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Invalid.class, result);
        }
    }

    @Nested
    @DisplayName("validate() error handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("should return Invalid when JWKS cache throws exception")
        void shouldReturnInvalidWhenJwksCacheThrows() throws Exception {
            final var token = createValidToken();
            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("Cache unavailable")));

            final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Invalid.class, result);
            final var invalid = (TokenValidationResult.Invalid) result;
            assertEquals("Token validation failed", invalid.reason());
        }

        @Test
        @DisplayName("should return Invalid when JWKS refresh throws exception")
        void shouldReturnInvalidWhenJwksRefreshThrows() throws Exception {
            final var token = createValidToken();
            when(jwksCache.getKey(TEST_JWKS_URI, TEST_KEY_ID))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            when(jwksCache.refresh(TEST_JWKS_URI))
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("Network error")));

            final var result = validator.validate(token, config).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(TokenValidationResult.Invalid.class, result);
            final var invalid = (TokenValidationResult.Invalid) result;
            assertEquals("Token validation failed", invalid.reason());
        }
    }

    // Helper methods to create test tokens

    private String createValidToken() throws Exception {
        return createToken(
                TEST_SUBJECT,
                TEST_ISSUER,
                TEST_AUDIENCE,
                Instant.now().plusSeconds(3600),
                keyPair,
                TEST_KEY_ID,
                Map.of());
    }

    private String createExpiredToken() throws Exception {
        return createToken(
                TEST_SUBJECT,
                TEST_ISSUER,
                TEST_AUDIENCE,
                Instant.now().minusSeconds(3600),
                keyPair,
                TEST_KEY_ID,
                Map.of());
    }

    private String createTokenWithIssuer(String issuer) throws Exception {
        return createToken(
                TEST_SUBJECT, issuer, TEST_AUDIENCE, Instant.now().plusSeconds(3600), keyPair, TEST_KEY_ID, Map.of());
    }

    private String createTokenWithAudience(String audience) throws Exception {
        return createToken(
                TEST_SUBJECT, TEST_ISSUER, audience, Instant.now().plusSeconds(3600), keyPair, TEST_KEY_ID, Map.of());
    }

    private String createTokenWithKey(KeyPair signingKey) throws Exception {
        return createToken(
                TEST_SUBJECT,
                TEST_ISSUER,
                TEST_AUDIENCE,
                Instant.now().plusSeconds(3600),
                signingKey,
                TEST_KEY_ID,
                Map.of());
    }

    private String createTokenWithKeyId(String keyId) throws Exception {
        return createToken(
                TEST_SUBJECT, TEST_ISSUER, TEST_AUDIENCE, Instant.now().plusSeconds(3600), keyPair, keyId, Map.of());
    }

    private String createTokenWithClaims(Map<String, Object> additionalClaims) throws Exception {
        return createToken(
                TEST_SUBJECT,
                TEST_ISSUER,
                TEST_AUDIENCE,
                Instant.now().plusSeconds(3600),
                keyPair,
                TEST_KEY_ID,
                additionalClaims);
    }

    private String createTokenWithoutSubject() throws Exception {
        final var claims = new JwtClaims();
        claims.setIssuer(TEST_ISSUER);
        claims.setExpirationTime(
                NumericDate.fromSeconds(Instant.now().plusSeconds(3600).getEpochSecond()));
        claims.setIssuedAt(NumericDate.now());

        final var jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(keyPair.getPrivate());
        jws.setKeyIdHeaderValue(TEST_KEY_ID);
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);

        return jws.getCompactSerialization();
    }

    private String createTokenWithoutKid() throws Exception {
        final var claims = new JwtClaims();
        claims.setSubject(TEST_SUBJECT);
        claims.setIssuer(TEST_ISSUER);
        claims.setAudience(TEST_AUDIENCE);
        claims.setExpirationTime(
                NumericDate.fromSeconds(Instant.now().plusSeconds(3600).getEpochSecond()));
        claims.setIssuedAt(NumericDate.now());
        claims.setGeneratedJwtId();

        final var jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(keyPair.getPrivate());
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
        // intentionally no setKeyIdHeaderValue so the null-kid path is exercised

        return jws.getCompactSerialization();
    }

    private String createToken(
            String subject,
            String issuer,
            String audience,
            Instant expiration,
            KeyPair signingKey,
            String keyId,
            Map<String, Object> additionalClaims)
            throws Exception {

        final var claims = new JwtClaims();
        claims.setSubject(subject);
        claims.setIssuer(issuer);
        claims.setExpirationTime(NumericDate.fromSeconds(expiration.getEpochSecond()));
        claims.setIssuedAt(NumericDate.now());
        claims.setGeneratedJwtId();

        if (audience != null) {
            claims.setAudience(audience);
        }

        for (var entry : additionalClaims.entrySet()) {
            claims.setClaim(entry.getKey(), entry.getValue());
        }

        final var jws = new JsonWebSignature();
        jws.setPayload(claims.toJson());
        jws.setKey(signingKey.getPrivate());
        jws.setKeyIdHeaderValue(keyId);
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);

        return jws.getCompactSerialization();
    }
}
