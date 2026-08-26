package aussie.adapter.in.auth;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.adapter.out.telemetry.SecurityMonitor;
import aussie.common.context.RouteContextAttributes;
import aussie.core.config.ApiKeyConfig;
import aussie.core.config.SessionConfig;
import aussie.core.model.auth.InboundCredentials;
import aussie.core.model.auth.Permission;
import aussie.core.model.session.Session;
import aussie.core.port.in.SessionManagement;
import aussie.core.service.auth.ApiKeyService;
import aussie.core.service.auth.TokenValidationService;
import aussie.core.util.SecureHash;

/** Parses inbound credentials once and dispatches each unambiguous credential type. */
@ApplicationScoped
@Priority(1)
public class CredentialAuthenticationMechanism implements HttpAuthenticationMechanism {

    private static final Logger LOG = Logger.getLogger(CredentialAuthenticationMechanism.class);
    private static final String AUTHORIZATION = "Authorization";

    private final TokenValidationService tokenValidationService;
    private final SessionConfig sessionConfig;
    private final SessionCookieManager cookieManager;
    private final SessionManagement sessionManagement;
    private final GatewayMetrics metrics;
    private final SecurityMonitor securityMonitor;
    private final ClientContextResolver clientContextResolver;
    private final boolean acceptLegacyApiKeys;
    private final AtomicBoolean noopWarningLogged = new AtomicBoolean();
    private final AtomicBoolean legacyWarningLogged = new AtomicBoolean();

    @Inject
    public CredentialAuthenticationMechanism(
            TokenValidationService tokenValidationService,
            SessionConfig sessionConfig,
            SessionCookieManager cookieManager,
            SessionManagement sessionManagement,
            GatewayMetrics metrics,
            SecurityMonitor securityMonitor,
            ClientContextResolver clientContextResolver,
            ApiKeyConfig apiKeyConfig) {
        this.tokenValidationService = tokenValidationService;
        this.sessionConfig = sessionConfig;
        this.cookieManager = cookieManager;
        this.sessionManagement = sessionManagement;
        this.metrics = metrics;
        this.securityMonitor = securityMonitor;
        this.clientContextResolver = clientContextResolver;
        this.acceptLegacyApiKeys = apiKeyConfig.acceptLegacyFormat();
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        if (Boolean.TRUE.equals(context.get(RouteContextAttributes.PUBLIC))) {
            return Uni.createFrom().nullItem();
        }
        if (isDangerousNoopEnabled()) {
            return Uni.createFrom().item(createNoopIdentity());
        }

        final var request = context.request();
        final var authorizationHeaders = request.headers().getAll(AUTHORIZATION);
        final var hasSessionCookie = sessionConfig.enabled() && cookieManager.hasSessionCookie(request);
        final var credentials = new InboundCredentials(authorizationHeaders, Optional.empty());

        final Uni<SecurityIdentity> result;
        if (credentials.hasConflictingCredentials() || (!authorizationHeaders.isEmpty() && hasSessionCookie)) {
            result = reject(context, "conflicting_authentication", "multiple");
        } else if (!authorizationHeaders.isEmpty()) {
            result = authenticateCredential(context, credentials.bearerToken().orElse(null), identityProviderManager);
        } else if (hasSessionCookie) {
            final var sessionId = cookieManager.extractSessionId(request);
            result = sessionId.isPresent()
                    ? authenticateSession(context, sessionId.get())
                    : reject(context, "invalid_session", "session");
        } else {
            result = Uni.createFrom().nullItem();
        }
        return result.invoke(identity -> attachVerifiedIdentity(context, identity));
    }

    private void attachVerifiedIdentity(RoutingContext context, SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return;
        }
        final String principalId = identity.getAttribute("principalId");
        if (principalId != null) {
            clientContextResolver.attachVerifiedIdentity(context, principalId, identity.getAttribute("credentialId"));
        }
    }

    private Uni<SecurityIdentity> authenticateCredential(
            RoutingContext context, String credential, IdentityProviderManager identityProviderManager) {
        if (credential == null) {
            return reject(context, "invalid_authorization", "authorization");
        }
        if (credential.isEmpty() || credential.indexOf(' ') >= 0) {
            return reject(context, "invalid_authorization", "bearer");
        }
        if (ApiKeyService.isVersionedKey(credential)) {
            return identityProviderManager.authenticate(new ApiKeyAuthenticationRequest(credential));
        }
        if (credential.startsWith(ApiKeyService.API_KEY_NAMESPACE)) {
            return reject(context, "invalid_api_key_format", "api_key");
        }
        if (looksLikeJwt(credential)) {
            if (!tokenValidationService.isEnabled()) {
                return reject(context, "invalid_token", "jwt");
            }
            return identityProviderManager.authenticate(new JwtAuthenticationRequest(credential));
        }
        if (acceptLegacyApiKeys && ApiKeyService.isLegacyKey(credential)) {
            if (legacyWarningLogged.compareAndSet(false, true)) {
                LOG.warn("Legacy unprefixed API-key authentication is enabled; migrate keys to aussie_v1_ format");
            }
            return identityProviderManager.authenticate(new ApiKeyAuthenticationRequest(credential));
        }
        return reject(context, "ambiguous_credential", "bearer");
    }

    private Uni<SecurityIdentity> authenticateSession(RoutingContext context, String sessionId) {
        if (LOG.isDebugEnabled()) {
            LOG.debugf("session_present hash=%s", SecureHash.truncatedSha256(sessionId, 8));
        }

        return sessionManagement.getSession(sessionId).flatMap(session -> {
            if (session.isEmpty()) {
                return reject(context, "invalid_session", "session");
            }
            if (sessionConfig.slidingExpiration()) {
                refreshSession(sessionId);
            }
            return Uni.createFrom().item(createSessionIdentity(session.get()));
        });
    }

    private void refreshSession(String sessionId) {
        sessionManagement
                .refreshSession(sessionId)
                .subscribe()
                .with(
                        ignored -> {
                            if (LOG.isDebugEnabled()) {
                                LOG.debugf("session_refreshed hash=%s", SecureHash.truncatedSha256(sessionId, 8));
                            }
                        },
                        error -> LOG.warn("Failed to refresh session"));
    }

    private Uni<SecurityIdentity> reject(RoutingContext context, String reason, String method) {
        final var clientIp = clientContextResolver.getOrCompute(context).resolvedIp();
        metrics.recordAuthFailure(reason, clientIp);
        securityMonitor.recordAuthFailure(clientIp, reason, method);
        return Uni.createFrom().failure(new AuthenticationFailedException(reason));
    }

    private SecurityIdentity createSessionIdentity(Session session) {
        return SecurityIdentityFactory.create(
                new SessionPrincipal(session.id(), session.userId()),
                session.permissions(),
                SecurityIdentityFactory.attributes(
                        "sessionId",
                        session.id(),
                        "principalId",
                        session.userId(),
                        "credentialId",
                        session.id(),
                        "userId",
                        session.userId(),
                        "issuer",
                        session.issuer(),
                        "claims",
                        session.claims(),
                        "expiresAt",
                        session.expiresAt(),
                        "authenticationMethod",
                        "session"));
    }

    private SecurityIdentity createNoopIdentity() {
        if (noopWarningLogged.compareAndSet(false, true)) {
            LOG.warn("DANGEROUS: Authentication is disabled (aussie.auth.dangerous-noop=true)");
        }
        return SecurityIdentityFactory.create(
                () -> "development-mode",
                Set.of(Permission.ALL.value()),
                Map.of("claims", Set.of(Permission.ALL.value()), "authenticationMethod", "dangerous-noop"));
    }

    private boolean isDangerousNoopEnabled() {
        return ConfigProvider.getConfig()
                .getOptionalValue("aussie.auth.dangerous-noop", Boolean.class)
                .orElse(false);
    }

    private static boolean looksLikeJwt(String token) {
        final var first = token.indexOf('.');
        final var second = first < 0 ? -1 : token.indexOf('.', first + 1);
        return first > 0 && second > first + 1 && second + 1 < token.length() && token.indexOf('.', second + 1) < 0;
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", "Bearer realm=\"aussie\""));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(ApiKeyAuthenticationRequest.class, JwtAuthenticationRequest.class);
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return Uni.createFrom().item(new HttpCredentialTransport(HttpCredentialTransport.Type.AUTHORIZATION, "Bearer"));
    }

    public static final class SessionPrincipal implements java.security.Principal {
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
