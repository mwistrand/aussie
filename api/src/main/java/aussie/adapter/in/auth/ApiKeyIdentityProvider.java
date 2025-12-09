package aussie.adapter.in.auth;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;

import aussie.core.model.ApiKey;
import aussie.core.port.in.ApiKeyManagement;

/**
 * Quarkus identity provider that validates API keys and builds SecurityIdentity.
 *
 * <p>This provider validates API keys via {@link ApiKeyManagement} and maps
 * the key's permissions to Quarkus Security roles using {@link PermissionRoleMapper}.
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
    private final PermissionRoleMapper roleMapper;

    @Inject
    public ApiKeyIdentityProvider(ApiKeyManagement apiKeyManagement, PermissionRoleMapper roleMapper) {
        this.apiKeyManagement = apiKeyManagement;
        this.roleMapper = roleMapper;
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
                .map(optApiKey -> optApiKey.orElseThrow(() -> new AuthenticationFailedException("Invalid API key")))
                .map(this::buildIdentity);
    }

    private SecurityIdentity buildIdentity(ApiKey apiKey) {
        // Map permissions to Quarkus Security roles
        var roles = roleMapper.toRoles(apiKey.permissions());

        // Build the security identity
        var builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(new ApiKeyPrincipal(apiKey.id(), apiKey.name()))
                .addRoles(roles)
                .addAttribute("keyId", apiKey.id())
                .addAttribute("permissions", apiKey.permissions());

        if (apiKey.expiresAt() != null) {
            builder.addAttribute("expiresAt", apiKey.expiresAt());
        }

        return builder.build();
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
