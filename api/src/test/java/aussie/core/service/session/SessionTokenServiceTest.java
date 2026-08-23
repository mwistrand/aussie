package aussie.core.service.session;

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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.SessionConfig;
import aussie.core.model.auth.SigningKeyRecord;
import aussie.core.model.session.Session;
import aussie.core.service.auth.SigningKeyRegistry;

@DisplayName("SessionTokenService")
class SessionTokenServiceTest {

    private static KeyPair testKeyPair;

    private SessionConfig config;
    private SessionConfig.JwsConfig jwsConfig;
    private SigningKeyRegistry keyRegistry;

    @BeforeAll
    static void generateKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        testKeyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        config = mock(SessionConfig.class);
        jwsConfig = mock(SessionConfig.JwsConfig.class);
        keyRegistry = mock(SigningKeyRegistry.class);

        when(config.jws()).thenReturn(jwsConfig);

        when(jwsConfig.enabled()).thenReturn(true);
        when(jwsConfig.ttl()).thenReturn(Duration.ofMinutes(5));
        when(jwsConfig.issuer()).thenReturn("aussie-gateway");
        when(jwsConfig.audience()).thenReturn(Optional.of("downstream-services"));
        when(jwsConfig.includeClaims()).thenReturn(List.of("sub", "email", "name", "roles"));

        final var signingKey = SigningKeyRecord.active(
                "test-key-v1", (RSAPrivateKey) testKeyPair.getPrivate(), (RSAPublicKey) testKeyPair.getPublic());
        when(keyRegistry.isReady()).thenReturn(true);
        when(keyRegistry.getCurrentSigningKey()).thenReturn(signingKey);
    }

    private SessionTokenService createService() {
        return new SessionTokenService(config, keyRegistry);
    }

    private Session testSession() {
        return new Session(
                "session-123",
                "user-1",
                "https://idp.example.com",
                Map.of("sub", "user-1", "email", "user@example.com", "name", "Test User"),
                Set.of("admin", "reader"),
                Instant.now(),
                Instant.now().plusSeconds(28800),
                Instant.now(),
                "test-agent",
                "127.0.0.1");
    }

    @Nested
    @DisplayName("signing authority")
    class SigningAuthorityTests {

        @Test
        @DisplayName("should load signing key when configured")
        void shouldLoadSigningKeyWhenConfigured() {
            var service = createService();

            assertTrue(service.isSigningAvailable());
        }

        @Test
        @DisplayName("should handle missing signing key")
        void shouldHandleMissingSigningKey() {
            when(keyRegistry.isReady()).thenReturn(false);

            var service = createService();

            assertFalse(service.isSigningAvailable());
        }
    }

    @Nested
    @DisplayName("isEnabled()")
    class IsEnabledTests {

        @Test
        @DisplayName("should return true when JWS enabled")
        void shouldReturnTrueWhenEnabled() {
            var service = createService();
            assertTrue(service.isEnabled());
        }

        @Test
        @DisplayName("should return false when JWS disabled")
        void shouldReturnFalseWhenDisabled() {
            when(jwsConfig.enabled()).thenReturn(false);
            var service = createService();
            assertFalse(service.isEnabled());
        }
    }

    @Nested
    @DisplayName("generateToken()")
    class GenerateTokenTests {

        @Test
        @DisplayName("should generate valid signed token")
        void shouldGenerateValidSignedToken() throws Exception {
            var service = createService();
            var session = testSession();

            var token = service.generateToken(session);

            assertNotNull(token.token());
            assertEquals("session-123", token.sessionId());
            assertFalse(token.isExpired());
            assertTrue(token.claims().contains("sub"));
            assertTrue(token.claims().contains("iss"));
            assertTrue(token.claims().contains("sid"));

            JwtConsumer consumer = new JwtConsumerBuilder()
                    .setVerificationKey(testKeyPair.getPublic())
                    .setExpectedIssuer("aussie-gateway")
                    .setExpectedAudience("downstream-services")
                    .setRequireSubject()
                    .build();

            JwtClaims claims = consumer.processToClaims(token.token());
            assertEquals("user-1", claims.getSubject());
            assertEquals("aussie-gateway", claims.getIssuer());
            assertEquals("session-123", claims.getStringClaimValue("sid"));
        }

        @Test
        @DisplayName("should include email and name claims from session")
        void shouldIncludeEmailAndNameClaims() throws Exception {
            var service = createService();
            var session = testSession();

            var token = service.generateToken(session);

            JwtConsumer consumer = new JwtConsumerBuilder()
                    .setVerificationKey(testKeyPair.getPublic())
                    .setSkipDefaultAudienceValidation()
                    .build();

            JwtClaims claims = consumer.processToClaims(token.token());
            assertEquals("user@example.com", claims.getStringClaimValue("email"));
            assertEquals("Test User", claims.getStringClaimValue("name"));
        }

        @Test
        @DisplayName("should include roles from session permissions")
        void shouldIncludeRolesFromPermissions() throws Exception {
            var service = createService();
            var session = testSession();

            var token = service.generateToken(session);

            JwtConsumer consumer = new JwtConsumerBuilder()
                    .setVerificationKey(testKeyPair.getPublic())
                    .setSkipDefaultAudienceValidation()
                    .build();

            JwtClaims claims = consumer.processToClaims(token.token());
            var roles = claims.getClaimValue("roles");
            assertNotNull(roles);
        }

        @Test
        @DisplayName("should include audience when configured")
        void shouldIncludeAudienceWhenConfigured() throws Exception {
            when(jwsConfig.audience()).thenReturn(Optional.of("test-audience"));
            var service = createService();
            var session = testSession();

            var token = service.generateToken(session);

            JwtConsumer consumer = new JwtConsumerBuilder()
                    .setVerificationKey(testKeyPair.getPublic())
                    .setExpectedAudience("test-audience")
                    .build();

            JwtClaims claims = consumer.processToClaims(token.token());
            assertTrue(claims.getAudience().contains("test-audience"));
        }

        @Test
        @DisplayName("should include additional claims")
        void shouldIncludeAdditionalClaims() throws Exception {
            var service = createService();
            var session = testSession();

            var token = service.generateToken(session, Map.of("custom_claim", "custom_value", "sub", "attacker"));

            JwtConsumer consumer = new JwtConsumerBuilder()
                    .setVerificationKey(testKeyPair.getPublic())
                    .setSkipDefaultAudienceValidation()
                    .build();

            JwtClaims claims = consumer.processToClaims(token.token());
            assertEquals("custom_value", claims.getStringClaimValue("custom_claim"));
            assertEquals("user-1", claims.getSubject());
        }

        @Test
        @DisplayName("should not outlive the source session")
        void shouldNotOutliveSession() {
            final var sessionExpiration =
                    Instant.now().plusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            final var session = testSession().withExpiresAt(sessionExpiration);

            final var token = createService().generateToken(session);

            assertEquals(sessionExpiration, token.expiresAt());
        }

        @Test
        @DisplayName("should set correct key ID header")
        void shouldSetCorrectKeyIdHeader() throws Exception {
            var service = createService();
            var session = testSession();

            var token = service.generateToken(session);

            String[] parts = token.token().split("\\.");
            String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]));
            assertTrue(headerJson.contains("test-key-v1"));
        }

        @Test
        @DisplayName("should throw when JWS disabled")
        void shouldThrowWhenJwsDisabled() {
            when(jwsConfig.enabled()).thenReturn(false);
            var service = createService();

            var ex = assertThrows(IllegalStateException.class, () -> service.generateToken(testSession()));
            assertEquals("JWS token generation is disabled", ex.getMessage());
        }

        @Test
        @DisplayName("should throw when signing key not available")
        void shouldThrowWhenSigningKeyNotAvailable() {
            when(keyRegistry.isReady()).thenReturn(false);
            when(keyRegistry.getCurrentSigningKey()).thenThrow(new IllegalStateException("missing"));
            var service = createService();

            var ex = assertThrows(
                    SessionTokenService.SessionTokenException.class, () -> service.generateToken(testSession()));
            assertEquals("JWS signing key not configured", ex.getMessage());
        }

        @Test
        @DisplayName("should handle session with null claims")
        void shouldHandleSessionWithNullClaims() {
            var service = createService();
            var session = new Session(
                    "session-123",
                    "user-1",
                    "issuer",
                    null,
                    null,
                    Instant.now(),
                    Instant.now().plusSeconds(3600),
                    Instant.now(),
                    null,
                    null);

            var token = service.generateToken(session);
            assertNotNull(token.token());
            assertEquals("session-123", token.sessionId());
        }

        @Test
        @DisplayName("should handle session with empty claims")
        void shouldHandleSessionWithEmptyClaims() {
            var service = createService();
            var session = new Session(
                    "session-123",
                    "user-1",
                    "issuer",
                    Map.of(),
                    Set.of(),
                    Instant.now(),
                    Instant.now().plusSeconds(3600),
                    Instant.now(),
                    null,
                    null);

            var token = service.generateToken(session);
            assertNotNull(token.token());
        }

        @Test
        @DisplayName("should only include configured claims from session")
        void shouldOnlyIncludeConfiguredClaims() throws Exception {
            when(jwsConfig.includeClaims()).thenReturn(List.of("sub"));
            var service = createService();
            var session = testSession();

            var token = service.generateToken(session);

            JwtConsumer consumer = new JwtConsumerBuilder()
                    .setVerificationKey(testKeyPair.getPublic())
                    .setSkipDefaultAudienceValidation()
                    .build();

            JwtClaims claims = consumer.processToClaims(token.token());
            assertFalse(claims.hasClaim("email"));
            assertFalse(claims.hasClaim("name"));
            assertFalse(claims.hasClaim("roles"));
        }

        @Test
        @DisplayName("should wrap JoseException in SessionTokenException")
        void shouldWrapJoseException() {
            var ex1 = new SessionTokenService.SessionTokenException("test");
            assertEquals("test", ex1.getMessage());

            var cause = new RuntimeException("cause");
            var ex2 = new SessionTokenService.SessionTokenException("test", cause);
            assertEquals("test", ex2.getMessage());
            assertEquals(cause, ex2.getCause());
        }
    }
}
