package aussie.adapter.in.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.SessionConfig;
import aussie.core.model.session.Session;

@DisplayName("SessionCookieManager")
class SessionCookieManagerTest {

    private SessionConfig config;
    private SessionConfig.CookieConfig cookieConfig;
    private SessionCookieManager manager;

    @BeforeEach
    void setUp() {
        config = mock(SessionConfig.class);
        cookieConfig = mock(SessionConfig.CookieConfig.class);
        when(config.cookie()).thenReturn(cookieConfig);

        when(cookieConfig.name()).thenReturn("aussie_session");
        when(cookieConfig.path()).thenReturn("/");
        when(cookieConfig.secure()).thenReturn(true);
        when(cookieConfig.httpOnly()).thenReturn(true);
        when(cookieConfig.sameSite()).thenReturn("Lax");
        when(cookieConfig.domain()).thenReturn(Optional.empty());

        manager = new SessionCookieManager(config);
    }

    private Session testSession(Instant expiresAt) {
        return new Session(
                "session-123",
                "user-1",
                "issuer",
                Map.of(),
                Set.of(),
                Instant.now(),
                expiresAt,
                Instant.now(),
                null,
                null);
    }

    @Nested
    @DisplayName("createCookie()")
    class CreateCookieTests {

        @Test
        @DisplayName("should create cookie with correct name and value")
        void shouldCreateCookieWithCorrectNameAndValue() {
            var session = testSession(Instant.now().plusSeconds(3600));
            var cookie = manager.createCookie(session);

            assertEquals("aussie_session", cookie.getName());
            assertEquals("session-123", cookie.getValue());
        }

        @Test
        @DisplayName("should set path, secure, and httpOnly")
        void shouldSetSecurityAttributes() {
            var session = testSession(Instant.now().plusSeconds(3600));
            var cookie = manager.createCookie(session);

            assertEquals("/", cookie.getPath());
            assertTrue(cookie.isSecure());
            assertTrue(cookie.isHttpOnly());
        }

        @Test
        @DisplayName("should set domain when configured")
        void shouldSetDomainWhenConfigured() {
            when(cookieConfig.domain()).thenReturn(Optional.of(".example.com"));
            var session = testSession(Instant.now().plusSeconds(3600));

            var cookie = manager.createCookie(session);

            assertEquals(".example.com", cookie.getDomain());
        }

        @Test
        @DisplayName("should set max age from session expiry")
        void shouldSetMaxAgeFromSessionExpiry() {
            var session = testSession(Instant.now().plusSeconds(3600));
            var cookie = manager.createCookie(session);
            assertTrue(cookie.getMaxAge() > 3500);
            assertTrue(cookie.getMaxAge() <= 3600);
        }

        @Test
        @DisplayName("should handle null expiresAt")
        void shouldHandleNullExpiresAt() {
            var session = testSession(null);
            var cookie = manager.createCookie(session);

            assertNotNull(cookie);
            // maxAge should not be set to a positive value when expiresAt is null (session cookie)
            assertTrue(cookie.getMaxAge() <= 0, "maxAge should not be positive for null expiresAt");
        }

        @Test
        @DisplayName("should parse Strict SameSite")
        void shouldParseStrictSameSite() {
            when(cookieConfig.sameSite()).thenReturn("Strict");
            manager = new SessionCookieManager(config);
            var session = testSession(Instant.now().plusSeconds(3600));

            var cookie = manager.createCookie(session);
            assertNotNull(cookie);
            assertEquals(CookieSameSite.STRICT, cookie.getSameSite());
        }

        @Test
        @DisplayName("should parse None SameSite")
        void shouldParseNoneSameSite() {
            when(cookieConfig.sameSite()).thenReturn("None");
            manager = new SessionCookieManager(config);
            var session = testSession(Instant.now().plusSeconds(3600));

            var cookie = manager.createCookie(session);
            assertNotNull(cookie);
            assertEquals(CookieSameSite.NONE, cookie.getSameSite());
        }

        @Test
        @DisplayName("should default to Lax for unknown SameSite")
        void shouldDefaultToLaxForUnknownSameSite() {
            when(cookieConfig.sameSite()).thenReturn("Unknown");
            manager = new SessionCookieManager(config);
            var session = testSession(Instant.now().plusSeconds(3600));

            var cookie = manager.createCookie(session);
            assertNotNull(cookie);
            assertEquals(CookieSameSite.LAX, cookie.getSameSite());
        }
    }

    @Nested
    @DisplayName("createLogoutCookie()")
    class CreateLogoutCookieTests {

        @Test
        @DisplayName("should create cookie with empty value and zero max age")
        void shouldCreateLogoutCookie() {
            var cookie = manager.createLogoutCookie();

            assertEquals("aussie_session", cookie.getName());
            assertEquals("", cookie.getValue());
            assertEquals(0, cookie.getMaxAge());
        }
    }

    @Nested
    @DisplayName("extractSessionId()")
    class ExtractSessionIdTests {

        @Test
        @DisplayName("should extract session ID from cookie")
        void shouldExtractSessionId() {
            var request = mock(HttpServerRequest.class);
            var cookie = Cookie.cookie("aussie_session", "session-456");
            when(request.getCookie("aussie_session")).thenReturn(cookie);

            var result = manager.extractSessionId(request);

            assertTrue(result.isPresent());
            assertEquals("session-456", result.get());
        }

        @Test
        @DisplayName("should return empty when no cookie")
        void shouldReturnEmptyWhenNoCookie() {
            var request = mock(HttpServerRequest.class);
            when(request.getCookie("aussie_session")).thenReturn(null);

            var result = manager.extractSessionId(request);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty when cookie value is blank")
        void shouldReturnEmptyWhenBlankValue() {
            var request = mock(HttpServerRequest.class);
            var cookie = Cookie.cookie("aussie_session", "");
            when(request.getCookie("aussie_session")).thenReturn(cookie);

            var result = manager.extractSessionId(request);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("hasSessionCookie()")
    class HasSessionCookieTests {

        @Test
        @DisplayName("should return true when cookie present")
        void shouldReturnTrueWhenPresent() {
            var request = mock(HttpServerRequest.class);
            var cookie = Cookie.cookie("aussie_session", "session-789");
            when(request.getCookie("aussie_session")).thenReturn(cookie);

            assertTrue(manager.hasSessionCookie(request));
        }

        @Test
        @DisplayName("should return false when no cookie")
        void shouldReturnFalseWhenNoCookie() {
            var request = mock(HttpServerRequest.class);
            when(request.getCookie("aussie_session")).thenReturn(null);

            assertFalse(manager.hasSessionCookie(request));
        }
    }

    @Nested
    @DisplayName("getCookieName()")
    class GetCookieNameTests {

        @Test
        @DisplayName("should return configured cookie name")
        void shouldReturnConfiguredName() {
            assertEquals("aussie_session", manager.getCookieName());
        }
    }
}
