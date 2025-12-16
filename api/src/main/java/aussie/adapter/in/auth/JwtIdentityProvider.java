package aussie.adapter.in.auth;

import java.security.Principal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.model.auth.Permission;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.port.in.GroupManagement;
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
 *   <li>Roles: Mapped from JWT groups/permissions claims</li>
 *   <li>Attributes: All JWT claims including groups and permissions</li>
 * </ul>
 */
@ApplicationScoped
public class JwtIdentityProvider implements IdentityProvider<JwtAuthenticationRequest> {

    private static final Logger LOG = Logger.getLogger(JwtIdentityProvider.class);

    private final TokenValidationService tokenValidationService;
    private final GroupManagement groupManagement;
    private final Permission roleMapper;

    @Inject
    public JwtIdentityProvider(
            TokenValidationService tokenValidationService, GroupManagement groupManagement, Permission roleMapper) {
        this.tokenValidationService = tokenValidationService;
        this.groupManagement = groupManagement;
        this.roleMapper = roleMapper;
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
        String subject = validResult.subject();
        Map<String, Object> claims = validResult.claims();
        Instant expiry = validResult.expiresAt();

        // Extract groups from claims
        @SuppressWarnings("unchecked")
        List<String> groups = claims.get("groups") instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of();

        // Extract direct permissions from claims
        @SuppressWarnings("unchecked")
        List<String> directPermissions = claims.get("permissions") instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of();

        // Expand groups to permissions
        return expandGroupsToPermissions(groups).map(groupPermissions -> {
            // Combine direct permissions with group-expanded permissions
            Set<String> allPermissions = new HashSet<>(directPermissions);
            allPermissions.addAll(groupPermissions);

            // Map permissions to Quarkus Security roles
            Set<String> roles = roleMapper.toRoles(allPermissions);

            // Build security identity
            var builder = QuarkusSecurityIdentity.builder()
                    .setPrincipal(new JwtPrincipal(subject, claims))
                    .addRoles(roles)
                    .addAttribute("claims", claims)
                    .addAttribute("groups", groups)
                    .addAttribute("permissions", allPermissions)
                    .addAttribute("expiresAt", expiry);

            LOG.debugv("JWT authenticated: subject={0}, groups={1}, permissions={2}", subject, groups, allPermissions);

            return builder.build();
        });
    }

    private Uni<Set<String>> expandGroupsToPermissions(List<String> groups) {
        if (groups.isEmpty()) {
            return Uni.createFrom().item(Set.of());
        }

        return groupManagement.expandGroups(new HashSet<>(groups));
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
            // Defensive copy to prevent external modification
            this.claims = claims != null ? Map.copyOf(claims) : Map.of();
        }

        @Override
        public String getName() {
            // Use name claim if available, otherwise use subject
            Object name = claims.get("name");
            return name != null ? name.toString() : subject;
        }

        public String getSubject() {
            return subject;
        }

        /**
         * Returns an unmodifiable view of the claims.
         */
        public Map<String, Object> getClaims() {
            return claims;
        }

        public Object getClaim(String name) {
            return claims.get(name);
        }
    }
}
