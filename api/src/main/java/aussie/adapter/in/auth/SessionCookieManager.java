package aussie.adapter.in.auth;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.core.http.HttpServerRequest;

import aussie.core.config.SessionConfig;
import aussie.core.model.session.Session;

/**
 * Manages session cookies - creation, extraction, and invalidation.
 */
@ApplicationScoped
public class SessionCookieManager {

    @Inject
    SessionConfig config;

    /**
     * Creates a session cookie for the given session.
     *
     * @param session The session to create a cookie for
     * @return The session cookie
     */
    public Cookie createCookie(Session session) {
        Cookie cookie = Cookie.cookie(config.cookie().name(), session.id())
                .setPath(config.cookie().path())
                .setSecure(config.cookie().secure())
                .setHttpOnly(config.cookie().httpOnly())
                .setSameSite(parseSameSite(config.cookie().sameSite()));

        // Set domain if configured
        config.cookie().domain().ifPresent(cookie::setDomain);

        // Set max age based on session TTL
        if (session.expiresAt() != null) {
            long maxAge = session.expiresAt().getEpochSecond()
                    - java.time.Instant.now().getEpochSecond();
            if (maxAge > 0) {
                cookie.setMaxAge(maxAge);
            }
        }

        return cookie;
    }

    /**
     * Creates a logout cookie that expires immediately.
     *
     * @return Cookie that clears the session
     */
    public Cookie createLogoutCookie() {
        return Cookie.cookie(config.cookie().name(), "")
                .setPath(config.cookie().path())
                .setSecure(config.cookie().secure())
                .setHttpOnly(config.cookie().httpOnly())
                .setSameSite(parseSameSite(config.cookie().sameSite()))
                .setMaxAge(0); // Expires immediately
    }

    /**
     * Extracts the session ID from a request's cookies.
     *
     * @param request The HTTP request
     * @return The session ID, or empty if not present
     */
    public Optional<String> extractSessionId(HttpServerRequest request) {
        Cookie cookie = request.getCookie(config.cookie().name());
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(cookie.getValue());
    }

    /**
     * Checks if a request has a session cookie.
     *
     * @param request The HTTP request
     * @return true if a session cookie is present
     */
    public boolean hasSessionCookie(HttpServerRequest request) {
        return extractSessionId(request).isPresent();
    }

    /**
     * Gets the configured cookie name.
     *
     * @return Cookie name
     */
    public String getCookieName() {
        return config.cookie().name();
    }

    private CookieSameSite parseSameSite(String sameSite) {
        return switch (sameSite.toUpperCase()) {
            case "STRICT" -> CookieSameSite.STRICT;
            case "LAX" -> CookieSameSite.LAX;
            case "NONE" -> CookieSameSite.NONE;
            default -> CookieSameSite.LAX;
        };
    }
}
