package aussie.adapter.in.auth;

import java.util.Set;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

import aussie.core.service.auth.TokenValidationService;

/**
 * Quarkus HTTP authentication mechanism for JWT token authentication.
 *
 * <p>Extracts Bearer tokens from the Authorization header and creates
 * {@link JwtAuthenticationRequest} credentials for validation by
 * {@link JwtIdentityProvider}.
 *
 * <p>This mechanism has lower priority than {@link ApiKeyAuthenticationMechanism}
 * so API keys are tried first. JWT validation is only attempted for tokens
 * that don't look like API keys.
 *
 * <p>Example:
 * <pre>
 * Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...
 * </pre>
 */
@ApplicationScoped
@Priority(2) // Lower priority than ApiKeyAuthenticationMechanism (priority 1)
public class JwtAuthenticationMechanism implements HttpAuthenticationMechanism {

    private static final Logger LOG = Logger.getLogger(JwtAuthenticationMechanism.class);

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String API_KEY_PREFIX = "aussie_";

    private final TokenValidationService tokenValidationService;

    @Inject
    public JwtAuthenticationMechanism(TokenValidationService tokenValidationService) {
        this.tokenValidationService = tokenValidationService;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        // Skip if JWT validation is not enabled
        if (!tokenValidationService.isEnabled()) {
            return Uni.createFrom().nullItem();
        }

        String authHeader = context.request().getHeader(AUTHORIZATION_HEADER);

        // Check for Bearer token
        if (authHeader == null || authHeader.isBlank() || !authHeader.startsWith(BEARER_PREFIX)) {
            return Uni.createFrom().nullItem();
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            return Uni.createFrom().nullItem();
        }

        // Skip if this looks like an API key (let ApiKeyAuthenticationMechanism handle it)
        if (token.startsWith(API_KEY_PREFIX)) {
            return Uni.createFrom().nullItem();
        }

        // Looks like a JWT token - try to validate it
        LOG.debugv(
                "Attempting JWT authentication for path: {0}", context.request().path());
        JwtAuthenticationRequest request = new JwtAuthenticationRequest(token);
        return identityProviderManager.authenticate(request);
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        // Return 401 with WWW-Authenticate header for failed authentication
        return Uni.createFrom().item(new ChallengeData(401, "WWW-Authenticate", "Bearer realm=\"aussie\""));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of(JwtAuthenticationRequest.class);
    }

    @Override
    public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
        return Uni.createFrom().item(new HttpCredentialTransport(HttpCredentialTransport.Type.AUTHORIZATION, "Bearer"));
    }
}
