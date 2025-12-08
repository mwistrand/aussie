package aussie.adapter.in.auth;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Quarkus HTTP authentication mechanism for API key authentication.
 *
 * <p>
 * Extracts Bearer tokens from the Authorization header and creates
 * {@link ApiKeyAuthenticationRequest} credentials for validation by
 * {@link ApiKeyIdentityProvider}.
 *
 * <p>
 * Example:
 *
 * <pre>
 * Authorization: Bearer aussie_xxxxxxxxxxxx
 * </pre>
 */
@ApplicationScoped
@Priority(1)
public class ApiKeyAuthenticationMechanism implements HttpAuthenticationMechanism {

    private static final Logger LOG = Logger.getLogger(ApiKeyAuthenticationMechanism.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final AtomicBoolean noopWarningLogged = new AtomicBoolean();

    /**
     * Check if dangerous noop mode is enabled.
     */
    private boolean isDangerousNoopEnabled() {
        return ConfigProvider.getConfig()
                .getOptionalValue("aussie.auth.dangerous-noop", Boolean.class)
                .orElse(false);
    }

    /**
     * Creates a SecurityIdentity with all roles for noop mode.
     */
    private SecurityIdentity createNoopIdentity() {
        if (noopWarningLogged.compareAndSet(false, true)) {
            LOG.warn("⚠️  DANGEROUS: Authentication is DISABLED (aussie.auth.dangerous-noop=true)");
            LOG.warn("⚠️  All requests will be allowed without authentication!");
            LOG.warn("⚠️  Do NOT use this setting in production!");
        }

        return QuarkusSecurityIdentity.builder()
                .setPrincipal(() -> "development-mode")
                .addRole(PermissionRoleMapper.ROLE_ADMIN)
                .addRole(PermissionRoleMapper.ROLE_ADMIN_READ)
                .addRole(PermissionRoleMapper.ROLE_ADMIN_WRITE)
                .build();
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        String authHeader = context.request().getHeader(AUTHORIZATION_HEADER);

        // Check for Bearer token first - always validate API keys when provided
        if (authHeader != null && !authHeader.isBlank() && authHeader.startsWith(BEARER_PREFIX)) {
            String apiKey = authHeader.substring(BEARER_PREFIX.length()).trim();
            if (!apiKey.isBlank()) {
                // Create authentication request and delegate to identity provider
                ApiKeyAuthenticationRequest request = new ApiKeyAuthenticationRequest(apiKey);
                return identityProviderManager.authenticate(request);
            }
        }

        // No valid Bearer token - check if noop mode is enabled as fallback
        if (isDangerousNoopEnabled()) {
            return Uni.createFrom().item(createNoopIdentity());
        }

        // No auth header and noop disabled - return null to try other mechanisms
        return Uni.createFrom().nullItem();
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        // Return 401 with WWW-Authenticate header for failed authentication
        return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", "Bearer realm=\"aussie\""));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(ApiKeyAuthenticationRequest.class);
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return Uni.createFrom().item(new HttpCredentialTransport(HttpCredentialTransport.Type.AUTHORIZATION, "Bearer"));
    }
}
