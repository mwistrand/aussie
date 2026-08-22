package aussie.adapter.out.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.KeyRotationConfig;
import aussie.core.config.RouteAuthConfig;
import aussie.core.model.auth.SigningKeyRecord;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.common.JwsConfig;
import aussie.core.service.auth.SigningKeyRegistry;

@DisplayName("RsaTokenIssuer")
class RsaTokenIssuerTest {

    private static KeyPair testKeyPair;
    private static String testKeyPem;

    private RouteAuthConfig routeAuthConfig;
    private RouteAuthConfig.JwsProperties jwsProperties;
    private KeyRotationConfig keyRotationConfig;
    private SigningKeyRegistry keyRegistry;

    @BeforeAll
    static void generateKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        testKeyPair = generator.generateKeyPair();
        testKeyPem = Base64.getEncoder().encodeToString(testKeyPair.getPrivate().getEncoded());
    }

    @BeforeEach
    void setUp() {
        routeAuthConfig = mock(RouteAuthConfig.class);
        jwsProperties = mock(RouteAuthConfig.JwsProperties.class);
        keyRotationConfig = mock(KeyRotationConfig.class);
        keyRegistry = mock(SigningKeyRegistry.class);

        when(routeAuthConfig.enabled()).thenReturn(true);
        when(routeAuthConfig.jws()).thenReturn(jwsProperties);
        when(jwsProperties.signingKey()).thenReturn(Optional.of(testKeyPem));
        when(jwsProperties.keyId()).thenReturn("static-v1");
        when(keyRotationConfig.enabled()).thenReturn(false);
    }

    private RsaTokenIssuer createIssuer() {
        return new RsaTokenIssuer(routeAuthConfig, keyRotationConfig, keyRegistry);
    }

    private TokenValidationResult.Valid validToken() {
        return new TokenValidationResult.Valid(
                "user-1",
                "https://idp.example.com",
                Map.of("sub", "user-1", "email", "user@test.com", "name", "Test User"),
                Instant.now().plusSeconds(3600));
    }

    private JwsConfig jwsConfig() {
        return new JwsConfig(
                "aussie-gateway",
                "v1",
                Duration.ofMinutes(5),
                Duration.ofHours(24),
                Set.of("sub", "email", "name"),
                Optional.empty(),
                false);
    }

    @Nested
    @DisplayName("name()")
    class NameTests {

        @Test
        @DisplayName("should return rs256")
        void shouldReturnRs256() {
            assertEquals("rs256", createIssuer().name());
        }
    }

    @Nested
    @DisplayName("isAvailable()")
    class IsAvailableTests {

        @Test
        @DisplayName("should return true with static key when rotation disabled")
        void shouldReturnTrueWithStaticKey() {
            assertTrue(createIssuer().isAvailable());
        }

        @Test
        @DisplayName("should return false when no signing key configured")
        void shouldReturnFalseWithNoKey() {
            when(jwsProperties.signingKey()).thenReturn(Optional.empty());
            assertFalse(createIssuer().isAvailable());
        }

        @Test
        @DisplayName("should return false with invalid signing key")
        void shouldReturnFalseWithInvalidKey() {
            when(jwsProperties.signingKey()).thenReturn(Optional.of("not-a-valid-key"));
            assertFalse(createIssuer().isAvailable());
        }

        @Test
        @DisplayName("should use key registry when rotation enabled")
        void shouldUseKeyRegistryWhenRotationEnabled() {
            when(keyRotationConfig.enabled()).thenReturn(true);
            when(keyRegistry.isReady()).thenReturn(true);

            assertTrue(createIssuer().isAvailable());
        }

        @Test
        @DisplayName("should return false when rotation enabled but registry not ready")
        void shouldReturnFalseWhenRegistryNotReady() {
            when(keyRotationConfig.enabled()).thenReturn(true);
            when(keyRegistry.isReady()).thenReturn(false);

            assertFalse(createIssuer().isAvailable());
        }
    }

    @Nested
    @DisplayName("issue()")
    class IssueTests {

        @Test
        @DisplayName("should issue signed token with static key")
        void shouldIssueSignedToken() throws Exception {
            var issuer = createIssuer();
            var validated = validToken();
            var config = jwsConfig();

            var token = issuer.issue(validated, config);

            assertNotNull(token);
            assertEquals("user-1", token.subject());
            assertTrue(token.hasToken());

            var consumer = new JwtConsumerBuilder()
                    .setVerificationKey(testKeyPair.getPublic())
                    .setExpectedIssuer("aussie-gateway")
                    .setRequireSubject()
                    .build();

            var claims = consumer.processToClaims(token.jws());
            assertEquals("user-1", claims.getSubject());
            assertEquals("aussie-gateway", claims.getIssuer());
            assertEquals("https://idp.example.com", claims.getStringClaimValue("original_iss"));
            assertEquals("user@test.com", claims.getStringClaimValue("email"));
            assertEquals(
                    token.expiresAt().getEpochSecond(),
                    claims.getExpirationTime().getValue());
        }

        @Test
        @DisplayName("should include audience when provided")
        void shouldIncludeAudience() throws Exception {
            var issuer = createIssuer();
            var validated = validToken();
            var config = jwsConfig();

            var token = issuer.issue(validated, config, Optional.of("test-audience"));

            var consumer = new JwtConsumerBuilder()
                    .setVerificationKey(testKeyPair.getPublic())
                    .setExpectedAudience("test-audience")
                    .build();

            var claims = consumer.processToClaims(token.jws());
            assertTrue(claims.getAudience().contains("test-audience"));
        }

        @Test
        @DisplayName("should forward configured claims from original token")
        void shouldForwardConfiguredClaims() throws Exception {
            var issuer = createIssuer();
            var validated = validToken();
            var config = jwsConfig();

            var token = issuer.issue(validated, config);

            assertNotNull(token.claims());
            assertEquals("user@test.com", token.claims().get("email"));
            assertEquals("Test User", token.claims().get("name"));
        }

        @Test
        @DisplayName("should use key registry when rotation enabled")
        void shouldUseKeyRegistryWhenRotationEnabled() throws Exception {
            when(keyRotationConfig.enabled()).thenReturn(true);
            when(keyRegistry.isReady()).thenReturn(true);

            var signingKey = SigningKeyRecord.active(
                    "rotated-key-1", (RSAPrivateKey) testKeyPair.getPrivate(), (RSAPublicKey) testKeyPair.getPublic());
            when(keyRegistry.getCurrentSigningKey()).thenReturn(signingKey);

            var issuer = createIssuer();
            var token = issuer.issue(validToken(), jwsConfig());

            assertNotNull(token);

            String[] parts = token.jws().split("\\.");
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
            assertTrue(headerJson.contains("rotated-key-1"));
        }

        @Test
        @DisplayName("should throw when not available")
        void shouldThrowWhenNotAvailable() {
            when(jwsProperties.signingKey()).thenReturn(Optional.empty());
            var issuer = createIssuer();

            assertThrows(RsaTokenIssuer.TokenIssuanceException.class, () -> issuer.issue(validToken(), jwsConfig()));
        }

        @Test
        @DisplayName("should not forward standard claims as custom claims")
        void shouldNotForwardStandardClaims() throws Exception {
            var issuer = createIssuer();
            var validated = new TokenValidationResult.Valid(
                    "user-1",
                    "issuer",
                    Map.of("sub", "user-1", "iss", "original-issuer", "email", "test@test.com"),
                    Instant.now().plusSeconds(3600));
            var config = new JwsConfig(
                    "aussie-gateway",
                    "v1",
                    Duration.ofMinutes(5),
                    Duration.ofHours(24),
                    Set.of("sub", "iss", "email"),
                    Optional.empty(),
                    false);

            var token = issuer.issue(validated, config);

            var consumer = new JwtConsumerBuilder()
                    .setVerificationKey(testKeyPair.getPublic())
                    .setSkipDefaultAudienceValidation()
                    .build();

            var claims = consumer.processToClaims(token.jws());
            assertEquals("aussie-gateway", claims.getIssuer());
            assertEquals("test@test.com", claims.getStringClaimValue("email"));
        }

        @Test
        @DisplayName("should not outlive the validated upstream token")
        void shouldCapExpirationAtUpstreamExpiration() throws Exception {
            final var upstreamExpiration =
                    Instant.now().plusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            final var validated =
                    new TokenValidationResult.Valid("user-1", "issuer", Map.of("sub", "user-1"), upstreamExpiration);

            final var token = createIssuer().issue(validated, jwsConfig());
            final var claims = new JwtConsumerBuilder()
                    .setVerificationKey(testKeyPair.getPublic())
                    .setSkipDefaultAudienceValidation()
                    .build()
                    .processToClaims(token.jws());

            assertEquals(upstreamExpiration, token.expiresAt());
            assertEquals(
                    upstreamExpiration.getEpochSecond(),
                    claims.getExpirationTime().getValue());
        }

        @Test
        @DisplayName("should reject issuance without a valid upstream expiration")
        void shouldRejectInvalidUpstreamExpiration() {
            final var expired = new TokenValidationResult.Valid(
                    "user-1", "issuer", Map.of("sub", "user-1"), Instant.now().minusSeconds(1));
            final var missing = new TokenValidationResult.Valid("user-1", "issuer", Map.of("sub", "user-1"), null);

            assertThrows(RsaTokenIssuer.TokenIssuanceException.class, () -> createIssuer()
                    .issue(expired, jwsConfig()));
            assertThrows(RsaTokenIssuer.TokenIssuanceException.class, () -> createIssuer()
                    .issue(missing, jwsConfig()));
        }
    }

    @Nested
    @DisplayName("issue(validated, config) without audience")
    class IssueSyncTests {

        @Test
        @DisplayName("should delegate to issue with empty audience")
        void shouldDelegateWithEmptyAudience() {
            var issuer = createIssuer();
            var token = issuer.issue(validToken(), jwsConfig());

            assertNotNull(token);
            assertEquals("user-1", token.subject());
        }
    }
}
