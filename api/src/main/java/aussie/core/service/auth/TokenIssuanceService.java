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
import aussie.core.port.in.GroupManagement;
import aussie.spi.TokenIssuerProvider;

/**
 * Service that coordinates JWS token issuance.
 *
 * <p>Uses configured {@link TokenIssuerProvider} to sign tokens that Aussie
 * forwards to backend services.
 *
 * <p>When issuing tokens, this service expands group claims to their effective
 * permissions using the configured RBAC groups.
 */
@ApplicationScoped
public class TokenIssuanceService {

    private static final Logger LOG = Logger.getLogger(TokenIssuanceService.class);
    private static final String GROUPS_CLAIM = "groups";
    private static final String EFFECTIVE_PERMISSIONS_CLAIM = "effective_permissions";

    private final List<TokenIssuerProvider> issuers;
    private final GroupManagement groupManagement;
    private final JwsConfig jwsConfig;
    private final boolean enabled;

    @Inject
    public TokenIssuanceService(
            Instance<TokenIssuerProvider> issuerInstances, GroupManagement groupManagement, RouteAuthConfig config) {
        this.issuers = issuerInstances.stream()
                .filter(TokenIssuerProvider::isAvailable)
                .toList();
        this.groupManagement = groupManagement;

        this.enabled = config.enabled();
        if (enabled) {
            this.jwsConfig = new JwsConfig(
                    config.jws().issuer(),
                    config.jws().keyId(),
                    config.jws().tokenTtl(),
                    config.jws().forwardedClaims());
            LOG.infov("TokenIssuanceService initialized with {0} issuers", issuers.size());
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
     * Use {@link #issueAsync(TokenValidationResult.Valid)} for full group expansion.
     *
     * @param validated the validated incoming token
     * @return the signed Aussie token, or empty if issuance is not configured
     */
    public Optional<AussieToken> issue(TokenValidationResult.Valid validated) {
        if (!isEnabled()) {
            LOG.debug("Token issuance not enabled or no issuers available");
            return Optional.empty();
        }

        try {
            // Use the first available issuer
            final TokenIssuerProvider issuer = issuers.get(0);
            final AussieToken token = issuer.issue(validated, jwsConfig);
            LOG.debugv("Issued token for subject {0} using {1}", validated.subject(), issuer.name());
            return Optional.of(token);
        } catch (Exception e) {
            LOG.errorv(e, "Failed to issue token for subject {0}", validated.subject());
            return Optional.empty();
        }
    }

    /**
     * Issue a signed Aussie token with group expansion.
     *
     * <p>This method expands any groups in the token claims to their effective
     * permissions and adds them to the issued token.
     *
     * @param validated the validated incoming token
     * @return Uni with the signed Aussie token, or empty if issuance is not configured
     */
    public Uni<Optional<AussieToken>> issueAsync(TokenValidationResult.Valid validated) {
        if (!isEnabled()) {
            LOG.debug("Token issuance not enabled or no issuers available");
            return Uni.createFrom().item(Optional.empty());
        }

        // Extract groups from claims
        final Set<String> groups = extractGroups(validated.claims());

        if (groups.isEmpty()) {
            // No groups to expand, use synchronous issuance
            return Uni.createFrom().item(issue(validated));
        }

        // Expand groups to permissions
        return groupManagement.expandGroups(groups).map(expandedPermissions -> {
            try {
                // Create a new validated result with expanded permissions in claims
                final var enrichedValidated = enrichWithPermissions(validated, expandedPermissions);
                final TokenIssuerProvider issuer = issuers.get(0);
                final AussieToken token = issuer.issue(enrichedValidated, jwsConfig);
                LOG.debugv(
                        "Issued token for subject {0} with {1} groups expanded to {2} permissions",
                        validated.subject(), groups.size(), expandedPermissions.size());
                return Optional.of(token);
            } catch (Exception e) {
                LOG.errorv(e, "Failed to issue token for subject {0}", validated.subject());
                return Optional.<AussieToken>empty();
            }
        });
    }

    /**
     * Extract groups from token claims.
     *
     * @param claims the token claims
     * @return set of group IDs, empty if no groups claim present
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractGroups(Map<String, Object> claims) {
        final Object groupsClaim = claims.get(GROUPS_CLAIM);
        if (groupsClaim == null) {
            return Set.of();
        }

        final Set<String> groups = new HashSet<>();
        if (groupsClaim instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof String s) {
                    groups.add(s);
                }
            }
        } else if (groupsClaim instanceof String s) {
            // Single group as string
            groups.add(s);
        }

        return groups;
    }

    /**
     * Create a new validated result with expanded permissions added to claims.
     *
     * @param original            the original validated result
     * @param expandedPermissions permissions expanded from groups
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
