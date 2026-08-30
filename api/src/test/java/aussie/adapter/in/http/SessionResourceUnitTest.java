package aussie.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.httpproblem.HttpProblem;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.auth.CredentialAuthenticationMechanism.SessionPrincipal;
import aussie.adapter.in.auth.SessionCookieManager;
import aussie.adapter.in.context.ClientContextResolver;
import aussie.common.context.ClientContext;
import aussie.core.config.SessionConfig;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.auth.ValidatedIdentity;
import aussie.core.model.session.Session;
import aussie.core.port.in.SessionManagement;
import aussie.core.service.auth.TokenValidationService;

@ExtendWith(MockitoExtension.class)
class SessionResourceUnitTest {

    @Mock
    private SessionManagement sessionManagement;

    @Mock
    private SessionCookieManager cookieManager;

    @Mock
    private SessionConfig config;

    @Mock
    private SecurityIdentity securityIdentity;

    @Mock
    private TokenValidationService tokenValidationService;

    @Mock
    private HttpServerRequest request;

    @Mock
    private RoutingContext routingContext;

    @Mock
    private ClientContextResolver clientContextResolver;

    private SessionResource resource;

    @BeforeEach
    void setUp() {
        lenient().when(routingContext.request()).thenReturn(request);
        lenient()
                .when(clientContextResolver.getOrCompute(routingContext))
                .thenReturn(new ClientContext(null, false, null));
        lenient()
                .when(cookieManager.createCsrfResponseCookie(any()))
                .thenReturn(new NewCookie.Builder("aussie_session_csrf")
                        .value("csrf-token")
                        .build());
        lenient()
                .when(cookieManager.createLogoutCsrfResponseCookie())
                .thenReturn(new NewCookie.Builder("aussie_session_csrf")
                        .value("")
                        .maxAge(0)
                        .build());
        resource = new SessionResource(
                sessionManagement,
                cookieManager,
                config,
                securityIdentity,
                tokenValidationService,
                routingContext,
                clientContextResolver);
    }

    @Test
    void rejectsInvalidTokenWithoutCreatingSession() {
        when(config.enabled()).thenReturn(true);
        when(config.publicCreationEnabled()).thenReturn(true);
        when(tokenValidationService.validate("unsigned-token"))
                .thenReturn(Uni.createFrom().item(new TokenValidationResult.Invalid("bad signature")));

        final var problem = assertThrows(HttpProblem.class, () -> resource.createSession(
                        new SessionResource.CreateSessionRequest("unsigned-token", null))
                .await()
                .atMost(Duration.ofSeconds(5)));

        assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), problem.getStatusCode());
        verify(sessionManagement, never()).createSession(any(), any(), any());
    }

    @Test
    void createsSessionOnlyFromValidatedIdentity() {
        when(config.enabled()).thenReturn(true);
        when(config.publicCreationEnabled()).thenReturn(true);
        when(request.getHeader("User-Agent")).thenReturn("test-agent");
        final var identity = identity();
        when(tokenValidationService.validate("signed-token"))
                .thenReturn(Uni.createFrom().item(new TokenValidationResult.Valid(identity)));
        final var session = session();
        when(sessionManagement.createSession(eq(identity), eq("test-agent"), eq("unknown")))
                .thenReturn(Uni.createFrom().item(session));
        when(cookieManager.createResponseCookie(session))
                .thenReturn(new NewCookie.Builder("aussie_session")
                        .value(session.id())
                        .build());

        final var response = resource.createSession(new SessionResource.CreateSessionRequest("signed-token", null))
                .await()
                .atMost(Duration.ofSeconds(5));

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertFalse(((Map<?, ?>) response.getEntity()).containsKey("sessionId"));
        assertTrue(response.getCookies().containsKey("aussie_session"));
        verify(sessionManagement).createSession(identity, "test-agent", "unknown");
    }

    @Test
    void rejectsExternalRedirectAfterValidatedSessionCreation() {
        when(config.enabled()).thenReturn(true);
        when(config.publicCreationEnabled()).thenReturn(true);
        final var identity = identity();
        when(tokenValidationService.validate("signed-token"))
                .thenReturn(Uni.createFrom().item(new TokenValidationResult.Valid(identity)));
        final var session = session();
        when(sessionManagement.createSession(identity, null, "unknown"))
                .thenReturn(Uni.createFrom().item(session));
        when(cookieManager.createResponseCookie(session))
                .thenReturn(new NewCookie.Builder("aussie_session")
                        .value(session.id())
                        .build());

        final var response = resource.createSession(
                        new SessionResource.CreateSessionRequest("signed-token", "https://attacker.example"))
                .await()
                .atMost(Duration.ofSeconds(5));

        assertEquals("/", response.getLocation().toString());
    }

    @Test
    void returnsCurrentSession() {
        when(config.enabled()).thenReturn(true);
        when(securityIdentity.isAnonymous()).thenReturn(false);
        when(securityIdentity.getPrincipal()).thenReturn(new SessionPrincipal("session-1", "user-1"));
        when(sessionManagement.getSession("session-1"))
                .thenReturn(Uni.createFrom().item(Optional.of(session())));

        final var response = resource.getSession().await().atMost(Duration.ofSeconds(5));

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertFalse(((Map<?, ?>) response.getEntity()).containsKey("sessionId"));
    }

    @Test
    void logsOutCurrentSession() {
        when(config.enabled()).thenReturn(true);
        when(securityIdentity.isAnonymous()).thenReturn(false);
        when(securityIdentity.getPrincipal()).thenReturn(new SessionPrincipal("session-1", "user-1"));
        when(sessionManagement.invalidateSession("session-1"))
                .thenReturn(Uni.createFrom().voidItem());
        when(cookieManager.createLogoutResponseCookie())
                .thenReturn(new NewCookie.Builder("aussie_session")
                        .value("")
                        .maxAge(0)
                        .build());

        final var response = resource.logout().await().atMost(Duration.ofSeconds(5));

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(sessionManagement).invalidateSession("session-1");
    }

    @Test
    void logsOutAllUserSessions() {
        when(config.enabled()).thenReturn(true);
        when(securityIdentity.isAnonymous()).thenReturn(false);
        when(securityIdentity.getPrincipal()).thenReturn(new SessionPrincipal("session-1", "user-1"));
        when(sessionManagement.invalidateAllUserSessions("user-1"))
                .thenReturn(Uni.createFrom().voidItem());
        when(cookieManager.createLogoutResponseCookie())
                .thenReturn(new NewCookie.Builder("aussie_session")
                        .value("")
                        .maxAge(0)
                        .build());

        final var response = resource.logoutAll().await().atMost(Duration.ofSeconds(5));

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(sessionManagement).invalidateAllUserSessions("user-1");
    }

    @Test
    void refreshesCurrentSession() {
        when(config.enabled()).thenReturn(true);
        when(securityIdentity.isAnonymous()).thenReturn(false);
        when(securityIdentity.getPrincipal()).thenReturn(new SessionPrincipal("session-1", "user-1"));
        final var session = session();
        when(sessionManagement.refreshSession("session-1"))
                .thenReturn(Uni.createFrom().item(Optional.of(session)));
        when(cookieManager.createResponseCookie(session))
                .thenReturn(new NewCookie.Builder("aussie_session")
                        .value(session.id())
                        .build());

        final var response = resource.refreshSession().await().atMost(Duration.ofSeconds(5));

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        assertFalse(((Map<?, ?>) response.getEntity()).containsKey("sessionId"));
        assertTrue(response.getCookies().containsKey("aussie_session_csrf"));
    }

    private ValidatedIdentity identity() {
        final var expiresAt = Instant.now().plusSeconds(3600);
        return ValidatedIdentity.fromValidatedClaims(
                "configured-idp",
                "user-1",
                "https://idp.example.com",
                Set.of("aussie"),
                Optional.of(Instant.now().minusSeconds(10)),
                Optional.of("token-1"),
                Map.of("sub", "user-1", "exp", expiresAt.getEpochSecond()),
                Optional.of("mfa"),
                expiresAt);
    }

    private Session session() {
        final var now = Instant.now();
        return new Session(
                "session-1",
                "user-1",
                "https://idp.example.com",
                Map.of("sub", "user-1"),
                Set.of(),
                now,
                now.plusSeconds(3600),
                now,
                "test-agent",
                null);
    }
}
