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

import aussie.core.config.RouteAuthConfig;
import aussie.core.config.SessionConfig;
import aussie.core.model.session.Session;

@DisplayName("SessionTokenService")
class SessionTokenServiceTest {

    private static KeyPair testKeyPair;
    private static String testKeyPem;

    private SessionConfig config;
    private SessionConfig.JwsConfig jwsConfig;
    private RouteAuthConfig routeAuthConfig;
    private RouteAuthConfig.JwsProperties jwsProperties;

    @BeforeAll
    static void generateKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        testKeyPair = generator.generateKeyPair();
        testKeyPem = Base64.getEncoder().encodeToString(testKeyPair.getPrivate().getEncoded());
    }

    @BeforeEach
    void setUp() {
        config = mock(SessionConfig.class);
        jwsConfig = mock(SessionConfig.JwsConfig.class);
        routeAuthConfig = mock(RouteAuthConfig.class);
        jwsProperties = mock(RouteAuthConfig.JwsProperties.class);

        when(config.jws()).thenReturn(jwsConfig);
        when(routeAuthConfig.jws()).thenReturn(jwsProperties);

        when(jwsConfig.enabled()).thenReturn(true);
        when(jwsConfig.ttl()).thenReturn(Duration.ofMinutes(5));
        when(jwsConfig.issuer()).thenReturn("aussie-gateway");
        when(jwsConfig.audience()).thenReturn(Optional.empty());
        when(jwsConfig.includeClaims()).thenReturn(List.of("sub", "email", "name", "roles"));

        when(jwsProperties.signingKey()).thenReturn(Optional.of(testKeyPem));
        when(jwsProperties.keyId()).thenReturn("test-key-v1");
    }

    private SessionTokenService createAndInit() {
        var service = new SessionTokenService(config, routeAuthConfig);
        service.init();
        return service;
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
    @DisplayName("init()")
    class InitTests {

        @Test
        @DisplayName("should load signing key when configured")
        void shouldLoadSigningKeyWhenConfigured() {
            var service = createAndInit();

            assertTrue(service.isSigningAvailable());
        }

        @Test
        @DisplayName("should handle missing signing key")
        void shouldHandleMissingSigningKey() {
            when(jwsProperties.signingKey()).thenReturn(Optional.empty());

            var service = createAndInit();

            assertFalse(service.isSigningAvailable());
        }

        @Test
        @DisplayName("should handle invalid signing key")
        void shouldHandleInvalidSigningKey() {
            when(jwsProperties.signingKey()).thenReturn(Optional.of("not-a-valid-key"));

            var service = createAndInit();

            assertFalse(service.isSigningAvailable());
        }
    }

    @Nested
    @DisplayName("isEnabled()")
    class IsEnabledTests {

        @Test
        @DisplayName("should return true when JWS enabled")
        void shouldReturnTrueWhenEnabled() {
            var service = createAndInit();
            assertTrue(service.isEnabled());
        }

        @Test
        @DisplayName("should return false when JWS disabled")
        void shouldReturnFalseWhenDisabled() {
            when(jwsConfig.enabled()).thenReturn(false);
            var service = createAndInit();
            assertFalse(service.isEnabled());
        }
    }

    @Nested
    @DisplayName("generateToken()")
    class GenerateTokenTests {

        @Test
        @DisplayName("should generate valid signed token")
        void shouldGenerateValidSignedToken() throws Exception {
            var service = createAndInit();
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
            var service = createAndInit();
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
            var service = createAndInit();
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
            var service = createAndInit();
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
            var service = createAndInit();
            var session = testSession();

            var token = service.generateToken(session, Map.of("custom_claim", "custom_value"));

            JwtConsumer consumer = new JwtConsumerBuilder()
                    .setVerificationKey(testKeyPair.getPublic())
                    .setSkipDefaultAudienceValidation()
                    .build();

            JwtClaims claims = consumer.processToClaims(token.token());
            assertEquals("custom_value", claims.getStringClaimValue("custom_claim"));
        }

        @Test
        @DisplayName("should set correct key ID header")
        void shouldSetCorrectKeyIdHeader() throws Exception {
            var service = createAndInit();
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
            var service = createAndInit();

            var ex = assertThrows(IllegalStateException.class, () -> service.generateToken(testSession()));
            assertEquals("JWS token generation is disabled", ex.getMessage());
        }

        @Test
        @DisplayName("should throw when signing key not available")
        void shouldThrowWhenSigningKeyNotAvailable() {
            when(jwsProperties.signingKey()).thenReturn(Optional.empty());
            var service = createAndInit();

            var ex = assertThrows(IllegalStateException.class, () -> service.generateToken(testSession()));
            assertEquals("JWS signing key not configured", ex.getMessage());
        }

        @Test
        @DisplayName("should handle session with null claims")
        void shouldHandleSessionWithNullClaims() {
            var service = createAndInit();
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
            var service = createAndInit();
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
            var service = createAndInit();
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
