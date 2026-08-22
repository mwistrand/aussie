package aussie.adapter.in.auth;

import java.util.Locale;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.NewCookie;

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

    private final SessionConfig config;

    @Inject
    public SessionCookieManager(SessionConfig config) {
        this.config = config;
    }

    /**
     * Create a session cookie for the given session.
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

    /** Create the JAX-RS response cookie used by REST endpoints. */
    public NewCookie createResponseCookie(Session session) {
        final var cookie = createCookie(session);
        final var builder = responseCookieBuilder(cookie);
        if (session.expiresAt() != null) {
            final var maxAge = session.expiresAt().getEpochSecond()
                    - java.time.Instant.now().getEpochSecond();
            if (maxAge > 0) {
                builder.maxAge((int) Math.min(maxAge, Integer.MAX_VALUE));
            }
        }
        return builder.build();
    }

    /**
     * Create a logout cookie that expires immediately.
     *
     * @return Cookie that clears the session
     */
    public Cookie createLogoutCookie() {
        final var cookie = Cookie.cookie(config.cookie().name(), "")
                .setPath(config.cookie().path())
                .setSecure(config.cookie().secure())
                .setHttpOnly(config.cookie().httpOnly())
                .setSameSite(parseSameSite(config.cookie().sameSite()))
                .setMaxAge(0); // Expires immediately
        config.cookie().domain().ifPresent(cookie::setDomain);
        return cookie;
    }

    /** Create the JAX-RS response cookie that clears the configured session cookie. */
    public NewCookie createLogoutResponseCookie() {
        return responseCookieBuilder(createLogoutCookie()).maxAge(0).build();
    }

    /**
     * Extract the session ID from a request's cookies.
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
     * Check if a request has a session cookie.
     *
     * @param request The HTTP request
     * @return true if a session cookie is present
     */
    public boolean hasSessionCookie(HttpServerRequest request) {
        return extractSessionId(request).isPresent();
    }

    /**
     * Get the configured cookie name.
     *
     * @return Cookie name
     */
    public String getCookieName() {
        return config.cookie().name();
    }

    private NewCookie.Builder responseCookieBuilder(Cookie cookie) {
        final var builder = new NewCookie.Builder(cookie.getName())
                .value(cookie.getValue())
                .path(cookie.getPath())
                .httpOnly(cookie.isHttpOnly())
                .secure(cookie.isSecure())
                .sameSite(NewCookie.SameSite.valueOf(cookie.getSameSite().name()));
        if (cookie.getDomain() != null) {
            builder.domain(cookie.getDomain());
        }
        return builder;
    }

    private CookieSameSite parseSameSite(String sameSite) {
        return switch (sameSite.toUpperCase(Locale.ROOT)) {
            case "STRICT" -> CookieSameSite.STRICT;
            case "LAX" -> CookieSameSite.LAX;
            case "NONE" -> CookieSameSite.NONE;
            default -> CookieSameSite.LAX;
        };
    }
}
