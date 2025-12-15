package aussie.core.service.session;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.config.SessionConfig;

/**
 * Centralized feature flag service for session management.
 *
 * <p>Provides methods to check if various session-related features
 * are enabled, ensuring consistent behavior across all components.
 */
@ApplicationScoped
public class SessionFeatureFlag {

    @Inject
    SessionConfig config;

    /**
     * Checks if session management is enabled.
     *
     * <p>When disabled:
     * <ul>
     *   <li>Session authentication mechanism skips processing</li>
     *   <li>Session endpoints return 404</li>
     *   <li>Conflicting auth filter is bypassed</li>
     *   <li>No session cookies are set</li>
     * </ul>
     *
     * @return true if sessions are enabled
     */
    public boolean isSessionsEnabled() {
        return config.enabled();
    }

    /**
     * Checks if JWS token generation is enabled.
     *
     * <p>Requires both sessions and JWS to be enabled.
     * When enabled, session-authenticated requests will have
     * JWS tokens injected for downstream services.
     *
     * @return true if JWS token generation is enabled
     */
    public boolean isJwsEnabled() {
        return config.enabled() && config.jws().enabled();
    }

    /**
     * Checks if sliding expiration is enabled.
     *
     * <p>When enabled, session TTL is refreshed on each request,
     * extending the session lifetime as long as the user is active.
     *
     * @return true if sliding expiration is enabled
     */
    public boolean isSlidingExpirationEnabled() {
        return config.enabled() && config.slidingExpiration();
    }

    /**
     * Checks if conflicting authentication detection is enabled.
     *
     * <p>When sessions are enabled, requests with both an Authorization
     * header and a session cookie are rejected with 400 Bad Request.
     *
     * @return true if conflict detection should be performed
     */
    public boolean isConflictDetectionEnabled() {
        return config.enabled();
    }
}
