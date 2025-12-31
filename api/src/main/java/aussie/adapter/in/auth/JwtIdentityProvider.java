package aussie.adapter.in.auth;

import java.security.Principal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.security.StringPermission;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.model.auth.Permission;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.port.in.RoleManagement;
import aussie.core.service.auth.TokenTranslationService;
import aussie.core.service.auth.TokenValidationService;

/**
 * Quarkus identity provider that validates JWT tokens from configured OIDC providers.
 *
 * <p>This provider validates JWT tokens via {@link TokenValidationService} and maps
 * the token's claims to Quarkus Security roles.
 *
 * <p>The resulting {@link SecurityIdentity} contains:
 * <ul>
 *   <li>Principal name: The subject (sub) claim from the JWT</li>
 *   <li>Roles: Mapped from JWT roles/permissions claims</li>
 *   <li>Attributes: All JWT claims including roles and permissions</li>
 * </ul>
 */
@ApplicationScoped
public class JwtIdentityProvider implements IdentityProvider<JwtAuthenticationRequest> {

    private static final Logger LOG = Logger.getLogger(JwtIdentityProvider.class);

    private final TokenValidationService tokenValidationService;
    private final RoleManagement roleManagement;
    private final TokenTranslationService tokenTranslationService;

    @Inject
    public JwtIdentityProvider(
            TokenValidationService tokenValidationService,
            RoleManagement roleManagement,
            TokenTranslationService tokenTranslationService) {
        this.tokenValidationService = tokenValidationService;
        this.roleManagement = roleManagement;
        this.tokenTranslationService = tokenTranslationService;
    }

    @Override
    public Class<JwtAuthenticationRequest> getRequestType() {
        return JwtAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(JwtAuthenticationRequest request, AuthenticationRequestContext context) {

        if (!tokenValidationService.isEnabled()) {
            LOG.debug("JWT validation is not enabled");
            return Uni.createFrom().nullItem();
        }

        return tokenValidationService.validate(request.getToken()).flatMap(result -> {
            if (result instanceof TokenValidationResult.Valid valid) {
                return buildIdentity(valid);
            } else if (result instanceof TokenValidationResult.Invalid invalid) {
                LOG.debugv("JWT validation failed: {0}", invalid.reason());
                return Uni.createFrom()
                        .failure(new io.quarkus.security.AuthenticationFailedException(invalid.reason()));
            } else {
                return Uni.createFrom().nullItem();
            }
        });
    }

    private Uni<SecurityIdentity> buildIdentity(TokenValidationResult.Valid validResult) {
        final var subject = validResult.subject();
        final var issuer = validResult.issuer();
        final var claims = validResult.claims();
        final var expiry = validResult.expiresAt();

        // Translate claims to roles and permissions
        return tokenTranslationService.translate(issuer, subject, claims).flatMap(translated -> {
            final var tokenRoles = new ArrayList<>(translated.roles());
            final var directPermissions = translated.permissions();

            // Expand roles to permissions
            return expandRolesToPermissions(tokenRoles).map(rolePermissions -> {
                // Combine direct permissions with role-expanded permissions
                final var allPermissions = new HashSet<String>(directPermissions);
                allPermissions.addAll(rolePermissions);

                // Map permissions to Quarkus Security roles
                final Set<String> securityRoles = Permission.toRoles(allPermissions);

                // Build security identity
                final var builder = QuarkusSecurityIdentity.builder()
                        .setPrincipal(new JwtPrincipal(subject, claims))
                        .addRoles(securityRoles)
                        .addAttribute("claims", claims)
                        .addAttribute("roles", tokenRoles)
                        .addAttribute("permissions", allPermissions)
                        .addAttribute("expiresAt", expiry);

                // Add StringPermission objects for @PermissionsAllowed checks
                for (var role : securityRoles) {
                    builder.addPermission(new StringPermission(role));
                }

                LOG.debugv(
                        "JWT authenticated: subject={0}, roles={1}, permissions={2}",
                        subject, tokenRoles, allPermissions);

                return builder.build();
            });
        });
    }

    private Uni<Set<String>> expandRolesToPermissions(List<String> roles) {
        if (roles.isEmpty()) {
            return Uni.createFrom().item(Set.of());
        }

        return roleManagement.expandRoles(new HashSet<>(roles));
    }

    /**
     * Principal representing an authenticated JWT user.
     *
     * <p>Claims are stored as an immutable copy to prevent external modification.
     */
    public static class JwtPrincipal implements Principal {
        private final String subject;
        private final Map<String, Object> claims;

        public JwtPrincipal(String subject, Map<String, Object> claims) {
            this.subject = subject;
            this.claims = claims != null ? Map.copyOf(claims) : Map.of();
        }

        @Override
        public String getName() {
            // Use name claim if available, otherwise use subject
            final Object name = claims.get("name");
            return name != null ? name.toString() : subject;
        }

        public String getSubject() {
            return subject;
        }

        /**
         * Returns an unmodifiable view of all claims from the JWT.
         *
         * @return immutable map of claim names to values
         */
        public Map<String, Object> getClaims() {
            return claims;
        }

        /**
         * Returns a specific claim value by name.
         *
         * @param name the claim name
         * @return the claim value, or null if not present
         */
        public Object getClaim(String name) {
            return claims.get(name);
        }
    }
}
