package aussie.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
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

@DisplayName("SessionResource unit tests")
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
    private HttpServerRequest mockRequest;

    private SessionResource resource;

    private static final Instant NOW = Instant.now();
    private static final Instant EXPIRES = NOW.plusSeconds(28800);

    @BeforeEach
    void setUp() throws Exception {
        resource = new SessionResource(sessionManagement, cookieManager, config, securityIdentity);

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

    private String buildJwt(String payloadJson) {
        final var header = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        final var payload =
                Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        final var signature =
                Base64.getUrlEncoder().withoutPadding().encodeToString("sig".getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + signature;
    }

    @Nested
    @DisplayName("createSession")
    class CreateSession {

        @Test
        @DisplayName("should use empty permissions when request permissions are null")
        void shouldUseEmptyPermissionsWhenNull() {
            when(config.enabled()).thenReturn(true);
            when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");
            when(mockRequest.remoteAddress()).thenReturn(null);

            final var session = createSession("session-123", "user-1");
            when(sessionManagement.createSession(anyString(), anyString(), any(), any(), anyString(), isNull()))
                    .thenReturn(Uni.createFrom().item(session));
            when(cookieManager.createCookie(any(Session.class))).thenReturn(createSessionCookie());

            final var request = new SessionResource.CreateSessionRequest("user-1", "issuer", Map.of(), null, null);

            final var response = resource.createSession(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should use empty claims when request claims are null")
        void shouldUseEmptyClaimsWhenNull() {
            when(config.enabled()).thenReturn(true);
            when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");
            when(mockRequest.remoteAddress()).thenReturn(null);

            final var session = createSession("session-123", "user-1");
            when(sessionManagement.createSession(anyString(), anyString(), any(), any(), anyString(), isNull()))
                    .thenReturn(Uni.createFrom().item(session));
            when(cookieManager.createCookie(any(Session.class))).thenReturn(createSessionCookie());

            final var request = new SessionResource.CreateSessionRequest("user-1", "issuer", null, Set.of(), null);

            final var response = resource.createSession(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
        }

        @Test
        @DisplayName("should not redirect when redirectUrl is blank")
        void shouldNotRedirectWhenRedirectUrlBlank() {
            when(config.enabled()).thenReturn(true);
            when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");
            when(mockRequest.remoteAddress()).thenReturn(null);

            final var session = createSession("session-123", "user-1");
            when(sessionManagement.createSession(anyString(), anyString(), any(), any(), anyString(), isNull()))
                    .thenReturn(Uni.createFrom().item(session));
            when(cookieManager.createCookie(any(Session.class))).thenReturn(createSessionCookie());

            final var request = new SessionResource.CreateSessionRequest("user-1", "issuer", Map.of(), Set.of(), "  ");

            final var response = resource.createSession(request).await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            assertNull(response.getLocation());
        }
    }

    @Nested
    @DisplayName("getSession")
    class GetSession {

        @Test
        @DisplayName("should return empty issuer when session issuer is null")
        void shouldReturnEmptyIssuerWhenNull() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(false);
            final var principal = new SessionPrincipal("session-123", "user-1");
            when(securityIdentity.getPrincipal()).thenReturn(principal);

            final var session = new Session(
                    "session-123",
                    "user-1",
                    null,
                    Map.of("sub", "user-1"),
                    Set.of("user"),
                    NOW,
                    EXPIRES,
                    NOW,
                    "TestAgent",
                    "127.0.0.1");
            when(sessionManagement.getSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));

            final var response = resource.getSession().await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            final var body = (Map<String, Object>) response.getEntity();
            assertEquals("", body.get("issuer"));
        }
    }

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("should return logged out when not anonymous but not SessionPrincipal")
        void shouldReturnLoggedOutWhenNotSessionPrincipal() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(false);
            when(securityIdentity.getPrincipal()).thenReturn(mock(Principal.class));

            final var response = resource.logout().await().atMost(Duration.ofSeconds(5));

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            final var body = (Map<String, Object>) response.getEntity();
            assertEquals("Logged out", body.get("message"));
            verify(sessionManagement, never()).invalidateSession(anyString());
        }
    }

    @Nested
    @DisplayName("logoutAll")
    class LogoutAll {

        @Test
        @DisplayName("should throw unauthorized when not anonymous but not SessionPrincipal")
        void shouldThrowUnauthorizedWhenNotSessionPrincipal() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(false);
            when(securityIdentity.getPrincipal()).thenReturn(mock(Principal.class));

            final var ex = assertThrows(HttpProblem.class, () -> resource.logoutAll());
            assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("refreshSession")
    class RefreshSession {

        @Test
        @DisplayName("should throw unauthorized when not anonymous but not SessionPrincipal")
        void shouldThrowUnauthorizedWhenNotSessionPrincipal() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(false);
            when(securityIdentity.getPrincipal()).thenReturn(mock(Principal.class));

            final var ex = assertThrows(HttpProblem.class, () -> resource.refreshSession());
            assertEquals(Response.Status.UNAUTHORIZED.getStatusCode(), ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("authCallback")
    class AuthCallback {

        @Test
        @DisplayName("should throw bad request when subject claim is blank")
        void shouldThrowBadRequestWhenSubjectClaimBlank() {
            when(config.enabled()).thenReturn(true);

            final var jwt = buildJwt("{\"sub\":\"\",\"iss\":\"test\"}");

            final var ex = assertThrows(HttpProblem.class, () -> resource.authCallback(jwt, "/dashboard"));
            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getStatusCode());
        }

        @Test
        @DisplayName("should use default issuer when iss claim missing")
        void shouldUseDefaultIssuerWhenIssMissing() {
            when(config.enabled()).thenReturn(true);
            when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");
            final var addr = mock(SocketAddress.class);
            when(addr.host()).thenReturn("127.0.0.1");
            when(mockRequest.remoteAddress()).thenReturn(addr);

            final var jwt = buildJwt("{\"sub\":\"user-1\"}");

            final var session = createSession("session-abc", "user-1");
            when(sessionManagement.createSession(eq("user-1"), eq("unknown"), any(), any(), anyString(), anyString()))
                    .thenReturn(Uni.createFrom().item(session));
            when(cookieManager.createCookie(any(Session.class))).thenReturn(createSessionCookie());

            final var response =
                    resource.authCallback(jwt, "/dashboard").await().atMost(Duration.ofSeconds(5));

            assertEquals(303, response.getStatus());
            verify(sessionManagement)
                    .createSession(eq("user-1"), eq("unknown"), any(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("should extract permissions from JWT claims")
        void shouldExtractPermissionsFromJwtClaims() {
            when(config.enabled()).thenReturn(true);
            when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");
            final var addr = mock(SocketAddress.class);
            when(addr.host()).thenReturn("127.0.0.1");
            when(mockRequest.remoteAddress()).thenReturn(addr);

            final var jwt = buildJwt("{\"sub\":\"user-1\",\"iss\":\"test\",\"permissions\":[\"read\",\"write\"]}");

            final var session = createSession("session-abc", "user-1");
            when(sessionManagement.createSession(eq("user-1"), eq("test"), any(), any(), anyString(), anyString()))
                    .thenReturn(Uni.createFrom().item(session));
            when(cookieManager.createCookie(any(Session.class))).thenReturn(createSessionCookie());

            final var response =
                    resource.authCallback(jwt, "/dashboard").await().atMost(Duration.ofSeconds(5));

            assertEquals(303, response.getStatus());
        }

        @Test
        @DisplayName("should use null ipAddress when remoteAddress is null")
        void shouldUseNullIpAddressWhenRemoteAddressNull() {
            when(config.enabled()).thenReturn(true);
            when(mockRequest.getHeader("User-Agent")).thenReturn(null);
            when(mockRequest.remoteAddress()).thenReturn(null);

            final var jwt = buildJwt("{\"sub\":\"user-1\",\"iss\":\"test\"}");

            final var session = createSession("session-abc", "user-1");
            when(sessionManagement.createSession(eq("user-1"), eq("test"), any(), any(), isNull(), isNull()))
                    .thenReturn(Uni.createFrom().item(session));
            when(cookieManager.createCookie(any(Session.class))).thenReturn(createSessionCookie());

            final var response =
                    resource.authCallback(jwt, "/dashboard").await().atMost(Duration.ofSeconds(5));

            assertEquals(303, response.getStatus());
        }

        @Test
        @DisplayName("should sanitize redirect URL to / when null")
        void shouldSanitizeRedirectUrlToSlashWhenNull() {
            when(config.enabled()).thenReturn(true);
            when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");
            final var addr = mock(SocketAddress.class);
            when(addr.host()).thenReturn("127.0.0.1");
            when(mockRequest.remoteAddress()).thenReturn(addr);

            final var jwt = buildJwt("{\"sub\":\"user-1\",\"iss\":\"test\"}");

            final var session = createSession("session-abc", "user-1");
            when(sessionManagement.createSession(anyString(), anyString(), any(), any(), anyString(), anyString()))
                    .thenReturn(Uni.createFrom().item(session));
            when(cookieManager.createCookie(any(Session.class))).thenReturn(createSessionCookie());

            final var response = resource.authCallback(jwt, null).await().atMost(Duration.ofSeconds(5));

            assertEquals(303, response.getStatus());
            assertEquals("/", response.getLocation().toString());
        }

        @Test
        @DisplayName("should transform failure to internalError")
        void shouldTransformFailureToInternalError() {
            when(config.enabled()).thenReturn(true);
            when(mockRequest.getHeader("User-Agent")).thenReturn("TestAgent");
            when(mockRequest.remoteAddress()).thenReturn(null);

            final var jwt = buildJwt("{\"sub\":\"user-1\",\"iss\":\"test\"}");

            when(sessionManagement.createSession(anyString(), anyString(), any(), any(), anyString(), isNull()))
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("DB error")));

            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> resource.authCallback(jwt, "/dashboard").await().atMost(Duration.ofSeconds(5)));

            assertEquals(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(), ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("decodeJwtClaims")
    class DecodeJwtClaims {

        @Test
        @DisplayName("should handle payload where padding is exactly 4 (no padding needed)")
        void shouldHandlePayloadNoPaddingNeeded() throws Exception {
            final var method = SessionResource.class.getDeclaredMethod("decodeJwtClaims", String.class);
            method.setAccessible(true);

            // Create a payload whose base64 length % 4 == 0 (no padding needed)
            // "{\"sub\":\"user-1234\"}" is 20 bytes, base64 = 28 chars (divisible by 4)
            final var header = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
            final var payload = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("{\"sub\":\"user-1234\"}".getBytes(StandardCharsets.UTF_8));
            final var signature =
                    Base64.getUrlEncoder().withoutPadding().encodeToString("sig".getBytes(StandardCharsets.UTF_8));
            final var jwt = header + "." + payload + "." + signature;

            @SuppressWarnings("unchecked")
            final var claims = (Map<String, Object>) method.invoke(resource, jwt);

            assertEquals("user-1234", claims.get("sub"));
        }

        @Test
        @DisplayName("should throw for invalid JSON payload")
        void shouldThrowForInvalidJsonPayload() throws Exception {
            final var method = SessionResource.class.getDeclaredMethod("decodeJwtClaims", String.class);
            method.setAccessible(true);

            final var header = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
            final var payload = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString("not-valid-json".getBytes(StandardCharsets.UTF_8));
            final var signature =
                    Base64.getUrlEncoder().withoutPadding().encodeToString("sig".getBytes(StandardCharsets.UTF_8));
            final var jwt = header + "." + payload + "." + signature;

            try {
                method.invoke(resource, jwt);
            } catch (InvocationTargetException e) {
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
        @DisplayName("should return / for URL without scheme that is not relative")
        void shouldReturnSlashForUrlWithoutScheme() throws Exception {
            // A URL like "evil.com/path" - no scheme, doesn't start with /
            assertEquals("/", sanitize("evil.com/path"));
        }

        @Test
        @DisplayName("should return / for absolute URL with invalid parse")
        void shouldReturnSlashForInvalidParseUrl() throws Exception {
            // Something that might cause URI.create to fail
            assertEquals("/", sanitize("http://evil .com/path"));
        }

        @Test
        @DisplayName("should return allowed 127.0.0.1 origin URL")
        void shouldReturnAllowed127OriginUrl() throws Exception {
            assertEquals("http://127.0.0.1:3000/callback", sanitize("http://127.0.0.1:3000/callback"));
        }

        @Test
        @DisplayName("should return / for disallowed origin with port")
        void shouldReturnSlashForDisallowedOriginWithPort() throws Exception {
            assertEquals("/", sanitize("http://evil.com:8080/steal"));
        }

        @Test
        @DisplayName("should return / for URL without port in non-allowed origin")
        void shouldReturnSlashForUrlWithoutPort() throws Exception {
            assertEquals("/", sanitize("https://example.com/path"));
        }

        @Test
        @DisplayName("should return valid relative path with multiple segments")
        void shouldReturnValidRelativePathWithMultipleSegments() throws Exception {
            assertEquals("/app/dashboard/settings", sanitize("/app/dashboard/settings"));
        }
    }

    @Nested
    @DisplayName("convertToJaxRsCookie")
    class ConvertToJaxRsCookie {

        @Test
        @DisplayName("should handle session with null expiresAt via reflection")
        void shouldHandleSessionWithNullExpiresAt() throws Exception {
            final var method = SessionResource.class.getDeclaredMethod(
                    "convertToJaxRsCookie", io.vertx.core.http.Cookie.class, Session.class);
            method.setAccessible(true);

            final var session =
                    new Session("session-123", "user-1", "issuer", Map.of(), Set.of(), NOW, null, NOW, null, null);
            final var vertxCookie = createSessionCookie();

            final var jaxrsCookie = (jakarta.ws.rs.core.NewCookie) method.invoke(resource, vertxCookie, session);

            assertNotNull(jaxrsCookie);
            // maxAge should be default (-1) when expiresAt is null
            assertEquals(-1, jaxrsCookie.getMaxAge());
        }

        @Test
        @DisplayName("should handle session with past expiresAt (maxAge <= 0)")
        void shouldHandleSessionWithPastExpiresAt() throws Exception {
            final var method = SessionResource.class.getDeclaredMethod(
                    "convertToJaxRsCookie", io.vertx.core.http.Cookie.class, Session.class);
            method.setAccessible(true);

            final var pastExpiry = Instant.now().minusSeconds(100);
            final var session = new Session(
                    "session-123", "user-1", "issuer", Map.of(), Set.of(), NOW, pastExpiry, NOW, null, null);
            final var vertxCookie = createSessionCookie();

            final var jaxrsCookie = (jakarta.ws.rs.core.NewCookie) method.invoke(resource, vertxCookie, session);

            assertNotNull(jaxrsCookie);
            // maxAge should be default (-1) since maxAge <= 0 so builder.maxAge is not called
            assertEquals(-1, jaxrsCookie.getMaxAge());
        }

        @Test
        @DisplayName("should include domain when present on vertx cookie")
        void shouldIncludeDomainWhenPresent() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(false);
            final var principal = new SessionPrincipal("session-123", "user-1");
            when(securityIdentity.getPrincipal()).thenReturn(principal);

            final var futureExpiry = Instant.now().plusSeconds(3600);
            final var session = new Session(
                    "session-123", "user-1", "issuer", Map.of(), Set.of(), NOW, futureExpiry, NOW, null, null);
            when(sessionManagement.refreshSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));

            final var vertxCookie = Cookie.cookie("aussie_session", "session-123")
                    .setPath("/")
                    .setHttpOnly(true)
                    .setSecure(true)
                    .setDomain("example.com");
            when(cookieManager.createCookie(any(Session.class))).thenReturn(vertxCookie);

            final var response = resource.refreshSession().await().atMost(Duration.ofSeconds(5));

            final var jaxrsCookie = response.getCookies().get("aussie_session");
            assertNotNull(jaxrsCookie);
            assertEquals("example.com", jaxrsCookie.getDomain());
        }

        @Test
        @DisplayName("should not set domain when null on vertx cookie")
        void shouldNotSetDomainWhenNull() {
            when(config.enabled()).thenReturn(true);
            when(securityIdentity.isAnonymous()).thenReturn(false);
            final var principal = new SessionPrincipal("session-123", "user-1");
            when(securityIdentity.getPrincipal()).thenReturn(principal);

            final var futureExpiry = Instant.now().plusSeconds(3600);
            final var session = new Session(
                    "session-123", "user-1", "issuer", Map.of(), Set.of(), NOW, futureExpiry, NOW, null, null);
            when(sessionManagement.refreshSession("session-123"))
                    .thenReturn(Uni.createFrom().item(Optional.of(session)));
            when(cookieManager.createCookie(any(Session.class))).thenReturn(createSessionCookie());

            final var response = resource.refreshSession().await().atMost(Duration.ofSeconds(5));

            final var jaxrsCookie = response.getCookies().get("aussie_session");
            assertNotNull(jaxrsCookie);
            assertNull(jaxrsCookie.getDomain());
        }
    }
}
