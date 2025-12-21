package aussie.adapter.in.auth;

import java.util.HashSet;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.StringPermission;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;

import aussie.adapter.out.telemetry.GatewayMetrics;
import aussie.adapter.out.telemetry.SecurityMonitor;
import aussie.core.model.auth.ApiKey;
import aussie.core.model.auth.Permission;
import aussie.core.port.in.ApiKeyManagement;

/**
 * Quarkus identity provider that validates API keys and builds SecurityIdentity.
 *
 * <p>This provider validates API keys via {@link ApiKeyManagement} and maps
 * the key's permissions to Quarkus Security roles using {@link Permission}.
 *
 * <p>The resulting {@link SecurityIdentity} contains:
 * <ul>
 *   <li>Principal name: The API key name</li>
 *   <li>Roles: Mapped from API key permissions</li>
 *   <li>Attributes: keyId, permissions, expiresAt</li>
 * </ul>
 */
@ApplicationScoped
public class ApiKeyIdentityProvider implements IdentityProvider<ApiKeyAuthenticationRequest> {

    private final ApiKeyManagement apiKeyManagement;
    private final GatewayMetrics metrics;
    private final SecurityMonitor securityMonitor;

    @Inject
    public ApiKeyIdentityProvider(
            ApiKeyManagement apiKeyManagement, GatewayMetrics metrics, SecurityMonitor securityMonitor) {
        this.apiKeyManagement = apiKeyManagement;
        this.metrics = metrics;
        this.securityMonitor = securityMonitor;
    }

    @Override
    public Class<ApiKeyAuthenticationRequest> getRequestType() {
        return ApiKeyAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            ApiKeyAuthenticationRequest request, AuthenticationRequestContext context) {

        return apiKeyManagement
                .validate(request.getApiKey())
                .map(optApiKey -> {
                    if (optApiKey.isEmpty()) {
                        // Record the authentication failure
                        metrics.recordAuthFailure("invalid_key", null);
                        securityMonitor.recordAuthFailure("api_key", "Invalid API key", null);
                        throw new AuthenticationFailedException("Invalid API key");
                    }
                    return optApiKey.get();
                })
                .map(this::buildIdentity);
    }

    private SecurityIdentity buildIdentity(ApiKey apiKey) {
        // Map permissions to Quarkus Security roles for @RolesAllowed checks
        var roles = Permission.toRoles(apiKey.permissions());

        // Build effective permissions for service-level authorization
        var effectivePermissions = buildEffectivePermissions(apiKey);

        // Build the security identity
        var builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(new ApiKeyPrincipal(apiKey.id(), apiKey.name()))
                .addRoles(roles)
                .addAttribute("keyId", apiKey.id())
                .addAttribute("permissions", effectivePermissions);

        // Add StringPermission objects for @PermissionsAllowed checks
        for (String permission : roles) {
            builder.addPermission(new StringPermission(permission));
        }

        if (apiKey.expiresAt() != null) {
            builder.addAttribute("expiresAt", apiKey.expiresAt());
        }

        return builder.build();
    }

    /**
     * Build effective permissions for service-level authorization.
     *
     * <p>This includes all permissions from the API key, with special handling:
     * <ul>
     *   <li>Wildcard permission (*) adds "aussie:admin" for full access</li>
     *   <li>All permissions are included (e.g., "demo-service.lead")</li>
     * </ul>
     */
    private Set<String> buildEffectivePermissions(ApiKey apiKey) {
        Set<String> effectivePermissions = new HashSet<>();

        // Add permissions for service-level authorization
        if (apiKey.permissions() != null) {
            for (String permission : apiKey.permissions()) {
                if (Permission.ALL.value().equals(permission)) {
                    // Wildcard grants full admin access
                    effectivePermissions.add(Permission.ADMIN_CLAIM.value());
                }
                effectivePermissions.add(permission);
            }
        }

        return effectivePermissions;
    }

    /**
     * Principal representing an authenticated API key.
     */
    public static class ApiKeyPrincipal implements java.security.Principal {
        private final String keyId;
        private final String name;

        public ApiKeyPrincipal(String keyId, String name) {
            this.keyId = keyId;
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        public String getKeyId() {
            return keyId;
        }
    }
}
