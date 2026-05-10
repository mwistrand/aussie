package aussie.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.core.Response;

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.auth.SessionAuthenticationMechanism.SessionPrincipal;
import aussie.adapter.in.auth.SessionCookieManager;
import aussie.core.config.SessionConfig;
import aussie.core.model.session.Session;
import aussie.core.port.in.SessionManagement;

@DisplayName("SessionResource")
@ExtendWith(MockitoExtension.class)
class SessionResourceTest {

    @Mock
    private SessionManagement sessionManagement;

    @Mock
    private SessionCookieManager cookieManager;

    @Mock
    private SessionConfig config;

    @Mock
    private SecurityIdentity securityIdentity;

    @Mock
    private HttpServerRequest mockRequest;

    private SessionResource resource;

    private static final Instant NOW = Instant.now();
    private static final Instant EXPIRES = NOW.plusSeconds(28800);

    @BeforeEach
    void setUp() throws Exception {
        resource = new SessionResource(sessionManagement, cookieManager, config, securityIdentity);

        // Inject the mock HttpServerRequest via reflection
        final var requestField = SessionResource.class.getDeclaredField("request");
        requestField.setAccessible(true);
        requestField.set(resource, mockRequest);
    }

    private Session createSession(String id, String userId) {
        return new Session(
                id,
                userId,
                "https://idp.example.com",
                Map.of("sub", userId),
                Set.of("user"),
                NOW,
                EXPIRES,
                NOW,
                "TestAgent",
                "127.0.0.1");
    }

    private Cookie createSessionCookie() {
        return Cookie.cookie("aussie_session", "session-123")
                .setPath("/")
                .setHttpOnly(true)
                .setSecure(true);
    }

    private Cookie createLogoutCookie() {
        return Cookie.cookie("aussie_session", "")
                .setPath("/")
                .setHttpOnly(true)
                .setSecure(true)
                .setMaxAge(0);
    }

    @Nested
    @DisplayName("createSession")
    class CreateSession {

        @Test
        @DisplayName("throws featureDisabled when sessions are disabled")
        void throwsFeatureDisabledWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            final var request = new SessionResource.CreateSessionRequest("user-1", "issuer", Map.of(), Set.of(), null);

            final var ex = assertThrows(HttpProblem.class, () -> resource.createSession(request));
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("returns session info with cookie on success")
        void returnsSessionInfoWithCookie() {
            when(config.enabled()).thenReturn(true);
            when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");
            final var addr = mock(SocketAddress.class);
            when(addr.host()).thenReturn("127.0.0.1");
            when(mockRequest.remoteAddress()).thenReturn(addr);

            final var session = createSession("session-123", "user-1");
            when(sessionManagement.createSession(anyString(), anyString(), any(), any(), anyString(), anyString()))
                    .thenReturn(Uni.createFrom().item(session));
            when(cookieManager.createCookie(any(Session.class))).thenReturn(createSessionCookie());

            final var request = new SessionResource.CreateSessionRequest("user-1", "issuer", Map.of(), Set.of(), null);

            final var response = resource.createSession(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            assertNotNull(response.getCookies().get("aussie_session"));
        }

        @Test
        @DisplayName("returns 303 redirect when redirect URL is provided")
        void returnsRedirectWhenRedirectUrlProvided() {
            when(config.enabled()).thenReturn(true);
            when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");
            final var addr = mock(SocketAddress.class);
            when(addr.host()).thenReturn("127.0.0.1");
            when(mockRequest.remoteAddress()).thenReturn(addr);

            final var session = createSession("session-123", "user-1");
            when(sessionManagement.createSession(anyString(), anyString(), any(), any(), anyString(), anyString()))
                    .thenReturn(Uni.createFrom().item(session));
            when(cookieManager.createCookie(any(Session.class))).thenReturn(createSessionCookie());

            final var request = new SessionResource.CreateSessionRequest(
                    "user-1", "issuer", Map.of(), Set.of(), "https://app.example.com/dashboard");

            final var response = resource.createSession(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(303, response.getStatus());
            assertEquals(
                    "https://app.example.com/dashboard", response.getLocation().toString());
        }

        @Test
        @DisplayName("transforms failure to internalError")
        void transformsFailureToInternalError() {
            when(config.enabled()).thenReturn(true);
            when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");
            when(mockRequest.remoteAddress()).thenReturn(null);

            when(sessionManagement.createSession(anyString(), anyString(), any(), any(), anyString(), any()))
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("DB error")));

            final var request = new SessionResource.CreateSessionRequest("user-1", "issuer", Map.of(), Set.of(), null);

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.createSession(request).await().atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("getSession")
    class GetSession {

        @Test
        @DisplayName("throws featureDisabled when sessions are disabled")
        void throwsFeatureDisabledWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            final var ex = assertThrows(HttpProblem.class, () -> resource.getSession());
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws unauthorized when identity is anonymous")
        void throwsUnauthorizedWhenAnonymous() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(true);

            final var ex = assertThrows(HttpProblem.class, () -> resource.getSession());
            assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws unauthorized when principal is not a SessionPrincipal")
        void throwsUnauthorizedWhenNotSessionPrincipal() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(false);
            when(securityIdentity.getPrincipal()).thenReturn(mock(Principal.class));

            final var ex = assertThrows(HttpProblem.class, () -> resource.getSession());
            assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws unauthorized when session is not found")
        void throwsUnauthorizedWhenSessionNotFound() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(false);
            final var principal = new SessionPrincipal("session-123", "user-1");
            when(securityIdentity.getPrincipal()).thenReturn(principal);
            when(sessionManagement.getSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            final var ex = assertThrows(
                    HttpProblem.class, () -> resource.getSession().await().atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("returns session info on success")
        void returnsSessionInfoOnSuccess() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(false);
            final var principal = new SessionPrincipal("session-123", "user-1");
            when(securityIdentity.getPrincipal()).thenReturn(principal);

            final var session = createSession("session-123", "user-1");
            when(sessionManagement.getSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));

            final var response = resource.getSession().await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            final var body = (Map<String, Object>) response.getEntity();
            assertEquals("session-123", body.get("sessionId"));
            assertEquals("user-1", body.get("userId"));
        }
    }

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("throws featureDisabled when sessions are disabled")
        void throwsFeatureDisabledWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            final var ex = assertThrows(HttpProblem.class, () -> resource.logout());
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("returns 'Logged out' without invalidation when anonymous")
        void returnsLoggedOutWhenAnonymous() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(true);

            final var response = resource.logout().await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            final var body = (Map<String, Object>) response.getEntity();
            assertEquals("Logged out", body.get("message"));
            verify(sessionManagement, never()).invalidateSession(anyString());
        }

        @Test
        @DisplayName("invalidates session and returns logout cookie when authenticated")
        void invalidatesSessionWhenAuthenticated() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(false);
            final var principal = new SessionPrincipal("session-123", "user-1");
            when(securityIdentity.getPrincipal()).thenReturn(principal);
            when(sessionManagement.invalidateSession("session-123"))
                    .thenReturn(Uni.createFrom().voidItem());
            when(cookieManager.createLogoutCookie()).thenReturn(createLogoutCookie());

            final var response = resource.logout().await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            verify(sessionManagement).invalidateSession("session-123");
            assertNotNull(response.getCookies().get("aussie_session"));
            assertEquals(0, response.getCookies().get("aussie_session").getMaxAge());
        }
    }

    @Nested
    @DisplayName("logoutAll")
    class LogoutAll {

        @Test
        @DisplayName("throws featureDisabled when sessions are disabled")
        void throwsFeatureDisabledWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            final var ex = assertThrows(HttpProblem.class, () -> resource.logoutAll());
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws unauthorized when anonymous")
        void throwsUnauthorizedWhenAnonymous() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(true);

            final var ex = assertThrows(HttpProblem.class, () -> resource.logoutAll());
            assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("invalidates all sessions when authenticated")
        void invalidatesAllSessionsWhenAuthenticated() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(false);
            final var principal = new SessionPrincipal("session-123", "user-1");
            when(securityIdentity.getPrincipal()).thenReturn(principal);
            when(sessionManagement.invalidateAllUserSessions("user-1"))
                    .thenReturn(Uni.createFrom().voidItem());
            when(cookieManager.createLogoutCookie()).thenReturn(createLogoutCookie());

            final var response = resource.logoutAll().await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            verify(sessionManagement).invalidateAllUserSessions("user-1");
            @SuppressWarnings("unchecked")
            final var body = (Map<String, Object>) response.getEntity();
            assertEquals("Logged out from all devices", body.get("message"));
        }
    }

    @Nested
    @DisplayName("refreshSession")
    class RefreshSession {

        @Test
        @DisplayName("throws featureDisabled when sessions are disabled")
        void throwsFeatureDisabledWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            final var ex = assertThrows(HttpProblem.class, () -> resource.refreshSession());
            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws unauthorized when anonymous")
        void throwsUnauthorizedWhenAnonymous() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(true);

            final var ex = assertThrows(HttpProblem.class, () -> resource.refreshSession());
            assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws unauthorized when session not found")
        void throwsUnauthorizedWhenSessionNotFound() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(false);
            final var principal = new SessionPrincipal("session-123", "user-1");
            when(securityIdentity.getPrincipal()).thenReturn(principal);
            when(sessionManagement.refreshSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            final var ex = assertThrows(
                    HttpProblem.class, () -> resource.refreshSession().await().atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("returns refreshed session info with new cookie on success")
        void returnsRefreshedSessionOnSuccess() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(false);
            final var principal = new SessionPrincipal("session-123", "user-1");
            when(securityIdentity.getPrincipal()).thenReturn(principal);

            final var session = createSession("session-123", "user-1");
            when(sessionManagement.refreshSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));
            when(cookieManager.createCookie(any(Session.class))).thenReturn(createSessionCookie());

            final var response = resource.refreshSession().await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            assertNotNull(response.getCookies().get("aussie_session"));
            @SuppressWarnings("unchecked")
            final var body = (Map<String, Object>) response.getEntity();
            assertEquals("session-123", body.get("sessionId"));
        }
    }

    @Nested
    @DisplayName("authCallback")
    class AuthCallback {

        @Test
        @DisplayName("throws featureDisabled when sessions are disabled")
        void throwsFeatureDisabledWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            final var ex = assertThrows(HttpProblem.class, () -> resource.authCallback("token", "/dashboard"));

            assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest when token is missing")
        void throwsBadRequestWhenTokenMissing() {
            when(config.enabled()).thenReturn(true);

            final var ex = assertThrows(HttpProblem.class, () -> resource.authCallback(null, "/dashboard"));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest when token is blank")
        void throwsBadRequestWhenTokenBlank() {
            when(config.enabled()).thenReturn(true);

            final var ex = assertThrows(HttpProblem.class, () -> resource.authCallback("  ", "/dashboard"));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest for invalid JWT format")
        void throwsBadRequestForInvalidJwt() {
            when(config.enabled()).thenReturn(true);

            final var ex = assertThrows(HttpProblem.class, () -> resource.authCallback("not-a-jwt", "/dashboard"));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("throws badRequest when subject claim is missing")
        void throwsBadRequestWhenSubjectMissing() {
            when(config.enabled()).thenReturn(true);

            // Build a JWT without "sub" claim
            final var header = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
            final var payload = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("{\"iss\":\"test\"}".getBytes(StandardCharsets.UTF_8));
            final var signature =
                    Base64.getUrlEncoder().withoutPadding().encodeToString("sig".getBytes(StandardCharsets.UTF_8));
            final var jwt = header + "." + payload + "." + signature;

            final var ex = assertThrows(HttpProblem.class, () -> resource.authCallback(jwt, "/dashboard"));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("creates session and redirects on success")
        void createsSessionAndRedirects() {
            when(config.enabled()).thenReturn(true);
            when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");
            final var addr = mock(SocketAddress.class);
            when(addr.host()).thenReturn("127.0.0.1");
            when(mockRequest.remoteAddress()).thenReturn(addr);

            final var header = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
            final var payload = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("{\"sub\":\"user-1\",\"iss\":\"https://idp.example.com\"}"
                            .getBytes(StandardCharsets.UTF_8));
            final var signature =
                    Base64.getUrlEncoder().withoutPadding().encodeToString("sig".getBytes(StandardCharsets.UTF_8));
            final var jwt = header + "." + payload + "." + signature;

            final var session = createSession("session-abc", "user-1");
            when(sessionManagement.createSession(
                            eq("user-1"), eq("https://idp.example.com"), any(), any(), anyString(), anyString()))
                    .thenReturn(Uni.createFrom().item(session));
            when(cookieManager.createCookie(any(Session.class))).thenReturn(createSessionCookie());

            final var response =
                    resource.authCallback(jwt, "/dashboard").await().atMost(Duration.ofSeconds(5));

            assertEquals(303, response.getStatus());
            assertEquals("/dashboard", response.getLocation().toString());
            assertNotNull(response.getCookies().get("aussie_session"));
        }
    }

    @Nested
    @DisplayName("decodeJwtClaims")
    class DecodeJwtClaims {

        @Test
        @DisplayName("decodes valid JWT with padding needed")
        void decodesValidJwtWithPadding() throws Exception {
            final var method = SessionResource.class.getDeclaredMethod("decodeJwtClaims", String.class);
            method.setAccessible(true);

            // Build a JWT where payload needs base64 padding
            final var header = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8));
            final var payload = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("{\"sub\":\"u1\",\"iss\":\"test-issuer\"}".getBytes(StandardCharsets.UTF_8));
            final var signature =
                    Base64.getUrlEncoder().withoutPadding().encodeToString("fake-sig".getBytes(StandardCharsets.UTF_8));
            final var jwt = header + "." + payload + "." + signature;

            @SuppressWarnings("unchecked")
            final var claims = (Map<String, Object>) method.invoke(resource, jwt);

            assertEquals("u1", claims.get("sub"));
            assertEquals("test-issuer", claims.get("iss"));
        }

        @Test
        @DisplayName("throws IllegalArgumentException for invalid JWT format")
        void throwsForInvalidFormat() throws Exception {
            final var method = SessionResource.class.getDeclaredMethod("decodeJwtClaims", String.class);
            method.setAccessible(true);

            try {
                method.invoke(resource, "only.two-parts");
            } catch (java.lang.reflect.InvocationTargetException e) {
                assertTrue(e.getCause() instanceof IllegalArgumentException);
                return;
            }
            throw new AssertionError("Expected IllegalArgumentException to be thrown");
        }
    }

    @Nested
    @DisplayName("sanitizeRedirectUrl")
    class SanitizeRedirectUrl {

        private String sanitize(String url) throws Exception {
            final var method = SessionResource.class.getDeclaredMethod("sanitizeRedirectUrl", String.class);
            method.setAccessible(true);
            return (String) method.invoke(resource, url);
        }

        @Test
        @DisplayName("returns '/' for null input")
        void returnsSlashForNull() throws Exception {
            assertEquals("/", sanitize(null));
        }

        @Test
        @DisplayName("returns '/' for blank input")
        void returnsSlashForBlank() throws Exception {
            assertEquals("/", sanitize("  "));
        }

        @Test
        @DisplayName("returns '/' for protocol-relative URL (//) ")
        void returnsSlashForProtocolRelative() throws Exception {
            assertEquals("/", sanitize("//evil.com/path"));
        }

        @Test
        @DisplayName("returns '/' for URL with backslash")
        void returnsSlashForBackslash() throws Exception {
            assertEquals("/", sanitize("/path\\to\\evil"));
        }

        @Test
        @DisplayName("returns '/' for URL with percent encoding")
        void returnsSlashForPercentEncoding() throws Exception {
            assertEquals("/", sanitize("/path%2F%2Fevil.com"));
        }

        @Test
        @DisplayName("returns valid relative URL as-is")
        void returnsValidRelativeUrlAsIs() throws Exception {
            assertEquals("/dashboard", sanitize("/dashboard"));
        }

        @Test
        @DisplayName("returns '/' for URL with embedded protocol (://)")
        void returnsSlashForEmbeddedProtocol() throws Exception {
            assertEquals("/", sanitize("/http://evil.com"));
        }

        @Test
        @DisplayName("returns '/' for URL with embedded credentials (@)")
        void returnsSlashForEmbeddedCredentials() throws Exception {
            assertEquals("/", sanitize("/user@evil.com"));
        }

        @Test
        @DisplayName("returns allowed origin URL as-is")
        void returnsAllowedOriginAsIs() throws Exception {
            assertEquals("http://localhost:3000/callback", sanitize("http://localhost:3000/callback"));
        }

        @Test
        @DisplayName("returns '/' for disallowed origin URL")
        void returnsSlashForDisallowedOrigin() throws Exception {
            assertEquals("/", sanitize("https://evil.com/steal-session"));
        }
    }

    @Nested
    @DisplayName("convertToJaxRsCookie")
    class ConvertToJaxRsCookie {

        @Test
        @DisplayName("converts Vert.x cookie to JAX-RS cookie with maxAge from session expiry")
        void convertsCookieWithMaxAge() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(false);
            final var principal = new SessionPrincipal("session-123", "user-1");
            when(securityIdentity.getPrincipal()).thenReturn(principal);

            // Session expires far in the future
            final var futureExpiry = Instant.now().plusSeconds(3600);
            final var session = new Session(
                    "session-123", "user-1", "issuer", Map.of(), Set.of(), NOW, futureExpiry, NOW, null, null);
            when(sessionManagement.refreshSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));

            final var vertxCookie = createSessionCookie();
            when(cookieManager.createCookie(any(Session.class))).thenReturn(vertxCookie);

            final var response = resource.refreshSession().await().atMost(Duration.ofSeconds(5));

            final var jaxrsCookie = response.getCookies().get("aussie_session");
            assertNotNull(jaxrsCookie);
            assertTrue(jaxrsCookie.getMaxAge() > 0, "maxAge should be positive for future expiry");
            assertEquals("/", jaxrsCookie.getPath());
            assertTrue(jaxrsCookie.isHttpOnly());
            assertTrue(jaxrsCookie.isSecure());
        }
    }
}
