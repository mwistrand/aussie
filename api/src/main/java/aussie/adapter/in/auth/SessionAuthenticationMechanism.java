package aussie.adapter.in.auth;

import java.util.Optional;
import java.util.Set;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.adapter.out.telemetry.SecurityMonitor;
import aussie.core.config.SessionConfig;
import aussie.core.model.auth.Permission;
import aussie.core.model.session.Session;
import aussie.core.port.in.SessionManagement;

/**
 * HTTP authentication mechanism for session-based authentication.
 *
 * <p>Extracts session cookies from requests and validates them against
 * the session store. Priority is 50 (between noop and API key).
 */
@ApplicationScoped
@Priority(50)
public class SessionAuthenticationMechanism implements HttpAuthenticationMechanism {

    private static final Logger LOG = Logger.getLogger(SessionAuthenticationMechanism.class);

    @Inject
    SessionConfig config;

    @Inject
    SessionCookieManager cookieManager;

    @Inject
    SessionManagement sessionManagement;

    @Inject
    Permission roleMapper;

    @Inject
    GatewayMetrics metrics;

    @Inject
    SecurityMonitor securityMonitor;

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        LOG.infof(
                "SessionAuthenticationMechanism.authenticate() called for path: %s",
                context.request().path());

        // Skip if sessions are disabled
        if (!config.enabled()) {
            LOG.info("Sessions disabled, skipping");
            return Uni.createFrom().nullItem();
        }

        // Extract session ID from cookie
        Optional<String> sessionIdOpt = cookieManager.extractSessionId(context.request());
        if (sessionIdOpt.isEmpty()) {
            LOG.info("No session cookie found");
            return Uni.createFrom().nullItem();
        }
        LOG.infof("Found session cookie: %s", sessionIdOpt.get());

        String sessionId = sessionIdOpt.get();

        // Validate session
        return sessionManagement.getSession(sessionId).flatMap(sessionOpt -> {
            if (sessionOpt.isEmpty()) {
                LOG.debugf("Session not found or invalid: %s", sessionId);
                // Record invalid session metrics (cookie was present but session not found)
                metrics.recordAuthFailure("invalid_session", null);
                securityMonitor.recordAuthFailure("session", "Session not found or expired", null);
                return Uni.createFrom().nullItem();
            }

            Session session = sessionOpt.get();

            // Refresh session (sliding expiration)
            if (config.slidingExpiration()) {
                sessionManagement
                        .refreshSession(sessionId)
                        .subscribe()
                        .with(
                                result -> LOG.debugf("Session refreshed: %s", sessionId),
                                error -> LOG.warnf("Failed to refresh session: %s", error.getMessage()));
            }

            // Build security identity from session
            SecurityIdentity identity = buildIdentity(session);
            return Uni.createFrom().item(identity);
        });
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        // Return 401 - client should redirect to login
        return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", "Session realm=\"aussie\""));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(); // No credential types - we use cookies directly
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return Uni.createFrom()
                .item(new HttpCredentialTransport(HttpCredentialTransport.Type.COOKIE, cookieManager.getCookieName()));
    }

    private SecurityIdentity buildIdentity(Session session) {
        // Map permissions to roles
        Set<String> roles = roleMapper.toRoles(session.permissions());

        var builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(new SessionPrincipal(session.id(), session.userId()))
                .addRoles(roles)
                .addAttribute("sessionId", session.id())
                .addAttribute("userId", session.userId());

        if (session.issuer() != null) {
            builder.addAttribute("issuer", session.issuer());
        }

        if (session.permissions() != null) {
            builder.addAttribute("permissions", session.permissions());
        }

        if (session.claims() != null) {
            builder.addAttribute("claims", session.claims());
        }

        return builder.build();
    }

    /**
     * Principal representing a session-authenticated user.
     */
    public static class SessionPrincipal implements java.security.Principal {
        private final String sessionId;
        private final String userId;

        public SessionPrincipal(String sessionId, String userId) {
            this.sessionId = sessionId;
            this.userId = userId;
        }

        @Override
        public String getName() {
            return userId;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getUserId() {
            return userId;
        }
    }
}
