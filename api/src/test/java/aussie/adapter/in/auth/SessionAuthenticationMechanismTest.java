package aussie.adapter.in.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.adapter.out.telemetry.SecurityMonitor;
import aussie.core.config.SessionConfig;
import aussie.core.model.session.Session;
import aussie.core.port.in.SessionManagement;

@DisplayName("SessionAuthenticationMechanism")
class SessionAuthenticationMechanismTest {

    private SessionConfig config;
    private SessionCookieManager cookieManager;
    private SessionManagement sessionManagement;
    private GatewayMetrics metrics;
    private SecurityMonitor securityMonitor;
    private IdentityProviderManager identityProviderManager;
    private RoutingContext routingContext;
    private HttpServerRequest httpRequest;
    private SessionAuthenticationMechanism mechanism;

    @BeforeEach
    void setUp() {
        config = mock(SessionConfig.class);
        cookieManager = mock(SessionCookieManager.class);
        sessionManagement = mock(SessionManagement.class);
        metrics = mock(GatewayMetrics.class);
        securityMonitor = mock(SecurityMonitor.class);
        identityProviderManager = mock(IdentityProviderManager.class);
        routingContext = mock(RoutingContext.class);
        httpRequest = mock(HttpServerRequest.class);

        when(routingContext.request()).thenReturn(httpRequest);
        when(httpRequest.path()).thenReturn("/api/test");
        when(config.enabled()).thenReturn(true);
        when(config.slidingExpiration()).thenReturn(false);
        when(cookieManager.getCookieName()).thenReturn("aussie_session");

        mechanism =
                new SessionAuthenticationMechanism(config, cookieManager, sessionManagement, metrics, securityMonitor);
    }

    private Session testSession() {
        return new Session(
                "session-123",
                "user-1",
                "https://idp.example.com",
                Map.of("sub", "user-1", "email", "user@test.com"),
                Set.of("admin", "service.config.read"),
                Instant.now(),
                Instant.now().plusSeconds(28800),
                Instant.now(),
                "test-agent",
                "127.0.0.1");
    }

    @Nested
    @DisplayName("authenticate()")
    class AuthenticateTests {

        @Test
        @DisplayName("should return null when sessions disabled")
        void shouldReturnNullWhenSessionsDisabled() {
            when(config.enabled()).thenReturn(false);

            var result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertNull(result);
            verify(cookieManager, never()).extractSessionId(httpRequest);
        }

        @Test
        @DisplayName("should return null when no session cookie")
        void shouldReturnNullWhenNoCookie() {
            when(cookieManager.extractSessionId(httpRequest)).thenReturn(Optional.empty());

            var result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertNull(result);
        }

        @Test
        @DisplayName("should return null when session not found")
        void shouldReturnNullWhenSessionNotFound() {
            when(cookieManager.extractSessionId(httpRequest)).thenReturn(Optional.of("expired-session"));
            when(sessionManagement.getSession("expired-session"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertNull(result);
            verify(metrics).recordAuthFailure("invalid_session", null);
            verify(securityMonitor).recordAuthFailure("session", "Session not found or expired", null);
        }

        @Test
        @DisplayName("should build SecurityIdentity from valid session")
        void shouldBuildIdentityFromValidSession() {
            var session = testSession();
            when(cookieManager.extractSessionId(httpRequest)).thenReturn(Optional.of("session-123"));
            when(sessionManagement.getSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));

            var identity = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertNotNull(identity);
            assertEquals("user-1", identity.getPrincipal().getName());
            assertTrue(identity.getRoles().contains("admin"));
            assertEquals("session-123", identity.getAttribute("sessionId"));
            assertEquals("user-1", identity.getAttribute("userId"));
            assertEquals("https://idp.example.com", identity.getAttribute("issuer"));
        }

        @Test
        @DisplayName("should refresh session when sliding expiration enabled")
        void shouldRefreshSessionWhenSlidingExpiration() {
            when(config.slidingExpiration()).thenReturn(true);
            var session = testSession();
            when(cookieManager.extractSessionId(httpRequest)).thenReturn(Optional.of("session-123"));
            when(sessionManagement.getSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));
            when(sessionManagement.refreshSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));

            var identity = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertNotNull(identity);
            verify(sessionManagement).refreshSession("session-123");
        }

        @Test
        @DisplayName("should not refresh session when sliding expiration disabled")
        void shouldNotRefreshWhenSlidingExpirationDisabled() {
            when(config.slidingExpiration()).thenReturn(false);
            var session = testSession();
            when(cookieManager.extractSessionId(httpRequest)).thenReturn(Optional.of("session-123"));
            when(sessionManagement.getSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));

            mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            verify(sessionManagement, never()).refreshSession(anyString());
        }

        @Test
        @DisplayName("should include permissions attribute when session has permissions")
        void shouldIncludePermissionsAttribute() {
            var session = testSession();
            when(cookieManager.extractSessionId(httpRequest)).thenReturn(Optional.of("session-123"));
            when(sessionManagement.getSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));

            var identity = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertNotNull(identity);
            Set<String> perms = identity.getAttribute("permissions");
            assertNotNull(perms);
            assertTrue(perms.contains("admin"));
        }

        @Test
        @DisplayName("should include claims attribute when session has claims")
        void shouldIncludeClaimsAttribute() {
            var session = testSession();
            when(cookieManager.extractSessionId(httpRequest)).thenReturn(Optional.of("session-123"));
            when(sessionManagement.getSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));

            var identity = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            Map<String, Object> claims = identity.getAttribute("claims");
            assertNotNull(claims);
            assertEquals("user@test.com", claims.get("email"));
        }

        @Test
        @DisplayName("should handle session with null optional fields")
        void shouldHandleSessionWithNullFields() {
            var session = new Session(
                    "session-123",
                    "user-1",
                    null,
                    null,
                    null,
                    Instant.now(),
                    Instant.now().plusSeconds(3600),
                    Instant.now(),
                    null,
                    null);
            when(cookieManager.extractSessionId(httpRequest)).thenReturn(Optional.of("session-123"));
            when(sessionManagement.getSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));

            var identity = mechanism
                    .authenticate(routingContext, identityProviderManager)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertNotNull(identity);
            assertEquals("user-1", identity.getPrincipal().getName());
        }
    }

    @Nested
    @DisplayName("getChallenge()")
    class GetChallengeTests {

        @Test
        @DisplayName("should return 401 challenge")
        void shouldReturn401Challenge() {
            var challenge = mechanism.getChallenge(routingContext).await().atMost(Duration.ofSeconds(1));

            assertNotNull(challenge);
            assertEquals(401, challenge.status);
        }
    }

    @Nested
    @DisplayName("getCredentialTypes()")
    class GetCredentialTypesTests {

        @Test
        @DisplayName("should return empty set")
        void shouldReturnEmptySet() {
            assertTrue(mechanism.getCredentialTypes().isEmpty());
        }
    }

    @Nested
    @DisplayName("getCredentialTransport()")
    class GetCredentialTransportTests {

        @Test
        @DisplayName("should return cookie transport")
        void shouldReturnCookieTransport() {
            var transport =
                    mechanism.getCredentialTransport(routingContext).await().atMost(Duration.ofSeconds(1));

            assertNotNull(transport);
            assertInstanceOf(HttpCredentialTransport.class, transport);
            assertEquals(HttpCredentialTransport.Type.COOKIE, transport.getTransportType());
            assertEquals("aussie_session", transport.getTypeTarget());
        }
    }

    @Nested
    @DisplayName("SessionPrincipal")
    class SessionPrincipalTests {

        @Test
        @DisplayName("should expose session ID and user ID")
        void shouldExposeSessionAndUserId() {
            var principal = new SessionAuthenticationMechanism.SessionPrincipal("sess-1", "user-1");

            assertEquals("user-1", principal.getName());
            assertEquals("user-1", principal.getUserId());
            assertEquals("sess-1", principal.getSessionId());
        }
    }
}
