package aussie.core.service.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.SessionConfig;
import aussie.core.model.auth.AussieToken;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.RouteAuthResult;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.model.session.Session;
import aussie.core.model.session.SessionToken;
import aussie.core.port.in.SessionManagement;
import aussie.core.service.auth.TokenIssuanceService;
import aussie.core.service.auth.TokenValidationService;
import aussie.core.service.session.SessionTokenService;

@DisplayName("RouteAuthenticationService")
class RouteAuthenticationServiceTest {

    private TokenValidationService validationService;
    private TokenIssuanceService issuanceService;
    private SessionManagement sessionManagement;
    private SessionTokenService sessionTokenService;
    private SessionConfig sessionConfig;
    private SessionConfig.CookieConfig cookieConfig;
    private RouteAuthenticationService service;

    @BeforeEach
    void setUp() {
        validationService = mock(TokenValidationService.class);
        issuanceService = mock(TokenIssuanceService.class);
        sessionManagement = mock(SessionManagement.class);
        sessionTokenService = mock(SessionTokenService.class);
        sessionConfig = mock(SessionConfig.class);
        cookieConfig = mock(SessionConfig.CookieConfig.class);
        when(sessionConfig.cookie()).thenReturn(cookieConfig);
        when(cookieConfig.name()).thenReturn("aussie_session");
        when(sessionConfig.enabled()).thenReturn(true);

        service = new RouteAuthenticationService(
                validationService, issuanceService, sessionManagement, sessionTokenService, sessionConfig);
    }

    private ServiceRegistration testService() {
        return ServiceRegistration.builder("test-service")
                .displayName("Test")
                .baseUrl(URI.create("http://localhost:8080"))
                .build();
    }

    private RouteMatch routeMatch(boolean authRequired) {
        var endpoint = new EndpointConfig(
                "/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), authRequired);
        return new RouteMatch(testService(), endpoint, "/api/test", Map.of());
    }

    private GatewayRequest request(Map<String, List<String>> headers) {
        return new GatewayRequest(
                "GET", "/api/test", headers, URI.create("http://localhost/api/test"), null, "127.0.0.1");
    }

    @Nested
    @DisplayName("when route does not require auth")
    class NoAuthRequired {

        @Test
        @DisplayName("should return NotRequired")
        void shouldReturnNotRequired() {
            var result = service.authenticate(request(Map.of()), routeMatch(false))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.NotRequired.class, result);
        }
    }

    @Nested
    @DisplayName("when both bearer token and session cookie are present")
    class ConflictingAuth {

        @Test
        @DisplayName("should return BadRequest")
        void shouldReturnBadRequest() {
            var headers = Map.of(
                    "Authorization", List.of("Bearer some-token"),
                    "Cookie", List.of("aussie_session=session-123"));

            var result = service.authenticate(request(headers), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.BadRequest.class, result);
        }
    }

    @Nested
    @DisplayName("when no auth is provided for protected route")
    class NoAuthProvided {

        @Test
        @DisplayName("should return Unauthorized")
        void shouldReturnUnauthorized() {
            var result = service.authenticate(request(Map.of()), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.Unauthorized.class, result);
        }
    }

    @Nested
    @DisplayName("bearer token authentication")
    class BearerTokenAuth {

        @Test
        @DisplayName("should authenticate valid bearer token")
        void shouldAuthenticateValidBearerToken() {
            var headers = Map.of("Authorization", List.of("Bearer valid-token"));
            var expiresAt = Instant.now().plusSeconds(3600);
            var validResult = new TokenValidationResult.Valid("user-1", "issuer", Map.of("sub", "user-1"), expiresAt);
            var aussieToken = new AussieToken("signed-token", "user-1", expiresAt, Map.of());

            when(validationService.validate("valid-token"))
                    .thenReturn(Uni.createFrom().item(validResult));
            when(issuanceService.issueAsync(any(), any(), anyString()))
                    .thenReturn(Uni.createFrom().item(Optional.of(aussieToken)));

            var result = service.authenticate(request(headers), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.Authenticated.class, result);
            assertEquals(
                    "user-1", ((RouteAuthResult.Authenticated) result).token().subject());
        }

        @Test
        @DisplayName("should return Unauthorized for invalid token")
        void shouldReturnUnauthorizedForInvalidToken() {
            var headers = Map.of("Authorization", List.of("Bearer invalid-token"));
            when(validationService.validate("invalid-token"))
                    .thenReturn(Uni.createFrom().item(new TokenValidationResult.Invalid("bad signature")));

            var result = service.authenticate(request(headers), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.Unauthorized.class, result);
        }

        @Test
        @DisplayName("should handle lowercase authorization header")
        void shouldHandleLowercaseAuthorizationHeader() {
            var headers = Map.of("authorization", List.of("Bearer valid-token"));
            var expiresAt = Instant.now().plusSeconds(3600);
            var validResult = new TokenValidationResult.Valid("user-1", "issuer", Map.of("sub", "user-1"), expiresAt);
            var aussieToken = new AussieToken("signed-token", "user-1", expiresAt, Map.of());

            when(validationService.validate("valid-token"))
                    .thenReturn(Uni.createFrom().item(validResult));
            when(issuanceService.issueAsync(any(), any(), anyString()))
                    .thenReturn(Uni.createFrom().item(Optional.of(aussieToken)));

            var result = service.authenticate(request(headers), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.Authenticated.class, result);
        }

        @Test
        @DisplayName("should return Unauthorized when no token result")
        void shouldReturnUnauthorizedWhenNoToken() {
            var headers = Map.of("Authorization", List.of("Bearer token"));
            when(validationService.validate("token"))
                    .thenReturn(Uni.createFrom().item(new TokenValidationResult.NoToken()));

            var result = service.authenticate(request(headers), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.Unauthorized.class, result);
        }

        @Test
        @DisplayName("should create minimal token when issuance fails")
        void shouldCreateMinimalTokenWhenIssuanceFails() {
            var headers = Map.of("Authorization", List.of("Bearer valid-token"));
            var expiresAt = Instant.now().plusSeconds(3600);
            var validResult = new TokenValidationResult.Valid("user-1", "issuer", Map.of("sub", "user-1"), expiresAt);

            when(validationService.validate("valid-token"))
                    .thenReturn(Uni.createFrom().item(validResult));
            when(issuanceService.issueAsync(any(), any(), anyString()))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = service.authenticate(request(headers), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.Authenticated.class, result);
            var authenticated = (RouteAuthResult.Authenticated) result;
            assertEquals("user-1", authenticated.token().subject());
            // Minimal token has empty JWS
            assertTrue(!authenticated.token().hasToken());
        }

        @Test
        @DisplayName("should not extract non-Bearer auth headers")
        void shouldNotExtractNonBearerAuthHeaders() {
            var headers = Map.of("Authorization", List.of("Basic dXNlcjpwYXNz"));

            var result = service.authenticate(request(headers), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.Unauthorized.class, result);
            verify(validationService, never()).validate(anyString());
        }
    }

    @Nested
    @DisplayName("session cookie authentication")
    class SessionCookieAuth {

        @Test
        @DisplayName("should authenticate valid session")
        void shouldAuthenticateValidSession() {
            var headers = Map.of("Cookie", List.of("aussie_session=session-123"));
            var session = new Session(
                    "session-123",
                    "user-1",
                    "issuer",
                    Map.of("sub", "user-1"),
                    Set.of(),
                    Instant.now(),
                    Instant.now().plusSeconds(3600),
                    Instant.now(),
                    "test-agent",
                    "127.0.0.1");
            var sessionToken = new SessionToken(
                    "signed-session-token", Instant.now().plusSeconds(300), "session-123", Set.of("sub"));

            when(sessionManagement.getSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));
            when(sessionTokenService.isEnabled()).thenReturn(true);
            when(sessionTokenService.isSigningAvailable()).thenReturn(true);
            when(sessionTokenService.generateToken(session)).thenReturn(sessionToken);

            var result = service.authenticate(request(headers), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.Authenticated.class, result);
            var authenticated = (RouteAuthResult.Authenticated) result;
            assertEquals("user-1", authenticated.token().subject());
            assertTrue(authenticated.authSessionId().isPresent());
            assertEquals("session-123", authenticated.authSessionId().get());
        }

        @Test
        @DisplayName("should return Unauthorized when session not found")
        void shouldReturnUnauthorizedWhenSessionNotFound() {
            var headers = Map.of("Cookie", List.of("aussie_session=expired-session"));

            when(sessionManagement.getSession("expired-session"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = service.authenticate(request(headers), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.Unauthorized.class, result);
        }

        @Test
        @DisplayName("should return Unauthorized when session token service is not enabled")
        void shouldReturnUnauthorizedWhenSessionTokenServiceNotEnabled() {
            var headers = Map.of("Cookie", List.of("aussie_session=session-123"));
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

            when(sessionManagement.getSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));
            when(sessionTokenService.isEnabled()).thenReturn(false);

            var result = service.authenticate(request(headers), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.Unauthorized.class, result);
        }

        @Test
        @DisplayName("should return Unauthorized when token generation fails")
        void shouldReturnUnauthorizedWhenTokenGenerationFails() {
            var headers = Map.of("Cookie", List.of("aussie_session=session-123"));
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

            when(sessionManagement.getSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));
            when(sessionTokenService.isEnabled()).thenReturn(true);
            when(sessionTokenService.isSigningAvailable()).thenReturn(true);
            when(sessionTokenService.generateToken(any()))
                    .thenThrow(new SessionTokenService.SessionTokenException("signing failed"));

            var result = service.authenticate(request(headers), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.Unauthorized.class, result);
        }

        @Test
        @DisplayName("should not extract session cookie when sessions disabled")
        void shouldNotExtractSessionCookieWhenDisabled() {
            when(sessionConfig.enabled()).thenReturn(false);
            service = new RouteAuthenticationService(
                    validationService, issuanceService, sessionManagement, sessionTokenService, sessionConfig);

            var headers = Map.of("Cookie", List.of("aussie_session=session-123"));

            var result = service.authenticate(request(headers), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            // Should be Unauthorized since no bearer token and session disabled
            assertInstanceOf(RouteAuthResult.Unauthorized.class, result);
            verify(sessionManagement, never()).getSession(anyString());
        }

        @Test
        @DisplayName("should parse session cookie from multiple cookies")
        void shouldParseSessionCookieFromMultipleCookies() {
            var headers = Map.of("Cookie", List.of("other=value; aussie_session=session-456; foo=bar"));
            var session = new Session(
                    "session-456",
                    "user-2",
                    "issuer",
                    Map.of("sub", "user-2"),
                    Set.of(),
                    Instant.now(),
                    Instant.now().plusSeconds(3600),
                    Instant.now(),
                    null,
                    null);
            var sessionToken = new SessionToken("token", Instant.now().plusSeconds(300), "session-456", Set.of());

            when(sessionManagement.getSession("session-456"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));
            when(sessionTokenService.isEnabled()).thenReturn(true);
            when(sessionTokenService.isSigningAvailable()).thenReturn(true);
            when(sessionTokenService.generateToken(session)).thenReturn(sessionToken);

            var result = service.authenticate(request(headers), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.Authenticated.class, result);
        }

        @Test
        @DisplayName("should ignore blank cookie values")
        void shouldIgnoreBlankCookieValues() {
            var headers = Map.of("Cookie", List.of("aussie_session="));

            var result = service.authenticate(request(headers), routeMatch(true))
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertInstanceOf(RouteAuthResult.Unauthorized.class, result);
            verify(sessionManagement, never()).getSession(anyString());
        }
    }
}
