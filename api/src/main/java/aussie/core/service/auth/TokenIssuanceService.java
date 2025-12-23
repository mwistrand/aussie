package aussie.core.service.auth;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.RouteAuthConfig;
import aussie.core.model.auth.AussieToken;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.model.common.JwsConfig;
import aussie.core.port.in.RoleManagement;
import aussie.spi.TokenIssuerProvider;

/**
 * Service that coordinates JWS token issuance.
 *
 * <p>Uses configured {@link TokenIssuerProvider} to sign tokens that Aussie
 * forwards to backend services.
 *
 * <p>When issuing tokens, this service expands role claims to their effective
 * permissions using the configured RBAC roles.
 */
@ApplicationScoped
public class TokenIssuanceService {

    private static final Logger LOG = Logger.getLogger(TokenIssuanceService.class);
    private static final String ROLES_CLAIM = "roles";
    private static final String EFFECTIVE_PERMISSIONS_CLAIM = "effective_permissions";

    private final List<TokenIssuerProvider> issuers;
    private final RoleManagement roleManagement;
    private final JwsConfig jwsConfig;
    private final boolean enabled;

    @Inject
    public TokenIssuanceService(
            Instance<TokenIssuerProvider> issuerInstances, RoleManagement roleManagement, RouteAuthConfig config) {
        this.issuers = issuerInstances.stream()
                .filter(TokenIssuerProvider::isAvailable)
                .toList();
        this.roleManagement = roleManagement;

        this.enabled = config.enabled();
        if (enabled) {
            this.jwsConfig = new JwsConfig(
                    config.jws().issuer(),
                    config.jws().keyId(),
                    config.jws().tokenTtl(),
                    config.jws().maxTokenTtl(),
                    config.jws().forwardedClaims(),
                    config.jws().defaultAudience(),
                    config.jws().requireAudience());
            LOG.infov(
                    "TokenIssuanceService initialized with {0} issuers, max TTL: {1}, default audience: {2}",
                    issuers.size(),
                    config.jws().maxTokenTtl(),
                    config.jws().defaultAudience().orElse("(none)"));
        } else {
            this.jwsConfig = null;
        }
    }

    /**
     * Check if token issuance is enabled and configured.
     */
    public boolean isEnabled() {
        return enabled && !issuers.isEmpty();
    }

    /**
     * Issue a signed Aussie token for the validated request.
     *
     * <p>This synchronous method is provided for backward compatibility.
     * Use {@link #issueAsync(TokenValidationResult.Valid)} for full role expansion.
     *
     * @param validated the validated incoming token
     * @return the signed Aussie token, or empty if issuance is not configured
     */
    public Optional<AussieToken> issue(TokenValidationResult.Valid validated) {
        return issue(validated, Optional.empty());
    }

    /**
     * Issue a signed Aussie token for the validated request with audience support.
     *
     * <p>This synchronous method is provided for backward compatibility.
     * Use {@link #issueAsync(TokenValidationResult.Valid, Optional, String)} for full role expansion.
     *
     * @param validated the validated incoming token
     * @param audience  the audience claim to include in the token
     * @return the signed Aussie token, or empty if issuance is not configured
     */
    public Optional<AussieToken> issue(TokenValidationResult.Valid validated, Optional<String> audience) {
        if (!isEnabled()) {
            LOG.debug("Token issuance not enabled or no issuers available");
            return Optional.empty();
        }

        try {
            // Use the first available issuer
            final TokenIssuerProvider issuer = issuers.get(0);
            final AussieToken token = issuer.issue(validated, jwsConfig, audience);
            LOG.debugv(
                    "Issued token for subject {0} using {1}, audience: {2}",
                    validated.subject(), issuer.name(), audience.orElse("(none)"));
            return Optional.of(token);
        } catch (Exception e) {
            LOG.errorv(e, "Failed to issue token for subject {0}", validated.subject());
            return Optional.empty();
        }
    }

    /**
     * Issue a signed Aussie token with role expansion.
     *
     * <p>This method expands any roles in the token claims to their effective
     * permissions and adds them to the issued token.
     *
     * @param validated the validated incoming token
     * @return Uni with the signed Aussie token, or empty if issuance is not configured
     */
    public Uni<Optional<AussieToken>> issueAsync(TokenValidationResult.Valid validated) {
        return issueAsync(validated, Optional.empty(), null);
    }

    /**
     * Issue a signed Aussie token with role expansion and audience support.
     *
     * <p>This method expands any roles in the token claims to their effective
     * permissions and adds them to the issued token. If an audience is configured
     * for the route, it will be included in the token.
     *
     * @param validated     the validated incoming token
     * @param routeAudience the audience configured for this route (if any)
     * @param serviceId     the service ID (used as fallback audience if required)
     * @return Uni with the signed Aussie token, or empty if issuance is not configured
     */
    public Uni<Optional<AussieToken>> issueAsync(
            TokenValidationResult.Valid validated, Optional<String> routeAudience, String serviceId) {
        if (!isEnabled()) {
            LOG.debug("Token issuance not enabled or no issuers available");
            return Uni.createFrom().item(Optional.empty());
        }

        // Resolve the effective audience
        final Optional<String> effectiveAudience = jwsConfig.resolveAudience(routeAudience, serviceId);

        // Extract roles from claims
        final Set<String> roles = extractRoles(validated.claims());

        if (roles.isEmpty()) {
            // No roles to expand, use synchronous issuance
            return Uni.createFrom().item(issue(validated, effectiveAudience));
        }

        // Expand roles to permissions
        return roleManagement.expandRoles(roles).map(expandedPermissions -> {
            try {
                // Create a new validated result with expanded permissions in claims
                final var enrichedValidated = enrichWithPermissions(validated, expandedPermissions);
                final TokenIssuerProvider issuer = issuers.get(0);
                final AussieToken token = issuer.issue(enrichedValidated, jwsConfig, effectiveAudience);
                LOG.debugv(
                        "Issued token for subject {0} with {1} roles expanded to {2} permissions, audience: {3}",
                        validated.subject(),
                        roles.size(),
                        expandedPermissions.size(),
                        effectiveAudience.orElse("(none)"));
                return Optional.of(token);
            } catch (Exception e) {
                LOG.errorv(e, "Failed to issue token for subject {0}", validated.subject());
                return Optional.<AussieToken>empty();
            }
        });
    }

    /**
     * Extract roles from token claims.
     *
     * @param claims the token claims
     * @return set of role IDs, empty if no roles claim present
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractRoles(Map<String, Object> claims) {
        final Object rolesClaim = claims.get(ROLES_CLAIM);
        if (rolesClaim == null) {
            return Set.of();
        }

        final Set<String> roles = new HashSet<>();
        if (rolesClaim instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof String s) {
                    roles.add(s);
                }
            }
        } else if (rolesClaim instanceof String s) {
            // Single role as string
            roles.add(s);
        }

        return roles;
    }

    /**
     * Create a new validated result with expanded permissions added to claims.
     *
     * @param original            the original validated result
     * @param expandedPermissions permissions expanded from roles
     * @return a new validated result with effective_permissions claim added
     */
    private TokenValidationResult.Valid enrichWithPermissions(
            TokenValidationResult.Valid original, Set<String> expandedPermissions) {
        final var enrichedClaims = new HashMap<>(original.claims());
        enrichedClaims.put(EFFECTIVE_PERMISSIONS_CLAIM, List.copyOf(expandedPermissions));

        return new TokenValidationResult.Valid(
                original.subject(), original.issuer(), enrichedClaims, original.expiresAt());
    }

    /**
     * Get the current JWS configuration.
     */
    public JwsConfig getJwsConfig() {
        return jwsConfig;
    }
}
