package aussie.adapter.in.auth;

import java.util.Optional;
import java.util.Set;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.security.StringPermission;
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
import aussie.core.util.SecureHash;

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

    private final SessionConfig config;
    private final SessionCookieManager cookieManager;
    private final SessionManagement sessionManagement;
    private final GatewayMetrics metrics;
    private final SecurityMonitor securityMonitor;

    @Inject
    public SessionAuthenticationMechanism(
            SessionConfig config,
            SessionCookieManager cookieManager,
            SessionManagement sessionManagement,
            GatewayMetrics metrics,
            SecurityMonitor securityMonitor) {
        this.config = config;
        this.cookieManager = cookieManager;
        this.sessionManagement = sessionManagement;
        this.metrics = metrics;
        this.securityMonitor = securityMonitor;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        if (LOG.isDebugEnabled()) {
            LOG.debugf(
                    "SessionAuthenticationMechanism.authenticate() called for path: %s",
                    context.request().path());
        }

        if (!config.enabled()) {
            return Uni.createFrom().nullItem();
        }

        Optional<String> sessionIdOpt = cookieManager.extractSessionId(context.request());
        if (sessionIdOpt.isEmpty()) {
            return Uni.createFrom().nullItem();
        }
        final String sessionId = sessionIdOpt.get();
        if (LOG.isDebugEnabled()) {
            LOG.debugf("session_present hash=%s", SecureHash.truncatedSha256(sessionId, 8));
        }

        return sessionManagement.getSession(sessionId).flatMap(sessionOpt -> {
            if (sessionOpt.isEmpty()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debugf("session_invalid hash=%s", SecureHash.truncatedSha256(sessionId, 8));
                }
                metrics.recordAuthFailure("invalid_session", null);
                securityMonitor.recordAuthFailure("session", "Session not found or expired", null);
                return Uni.createFrom().nullItem();
            }

            Session session = sessionOpt.get();

            if (config.slidingExpiration()) {
                sessionManagement
                        .refreshSession(sessionId)
                        .subscribe()
                        .with(
                                result -> {
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debugf(
                                                "session_refreshed hash=%s", SecureHash.truncatedSha256(sessionId, 8));
                                    }
                                },
                                error -> LOG.warnf("Failed to refresh session: %s", error.getMessage()));
            }

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
        Set<String> roles = Permission.toRoles(session.permissions());

        var builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(new SessionPrincipal(session.id(), session.userId()))
                .addRoles(roles)
                .addAttribute("sessionId", session.id())
                .addAttribute("userId", session.userId());

        // Add StringPermission objects for @PermissionsAllowed checks
        for (String role : roles) {
            builder.addPermission(new StringPermission(role));
        }

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
