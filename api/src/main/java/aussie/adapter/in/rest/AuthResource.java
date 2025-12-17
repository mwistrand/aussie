package aussie.adapter.in.rest;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkus.security.identity.SecurityIdentity;

import aussie.adapter.in.auth.ApiKeyIdentityProvider.ApiKeyPrincipal;
import aussie.core.model.auth.Permissions;

/**
 * REST resource for authentication-related endpoints.
 *
 * <p>
 * Provides endpoints for callers to introspect their own authentication state.
 */
@Path("/admin")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class AuthResource {

    private final SecurityIdentity identity;

    @Inject
    public AuthResource(SecurityIdentity identity) {
        this.identity = identity;
    }

    /**
     * Return information about the currently authenticated caller.
     *
     * <p>
     * This endpoint is useful for CLIs and automation to verify their
     * credentials are valid and inspect their permissions.
     *
     * <p>
     * Any authenticated user with any valid permission can access this endpoint.
     *
     * @return a map containing keyId, name, permissions, roles, and expiresAt (if
     *         applicable)
     */
    @GET
    @Path("/whoami")
    @RolesAllowed({
        Permissions.ADMIN,
        Permissions.SERVICE_CONFIG_READ,
        Permissions.SERVICE_CONFIG_CREATE,
        Permissions.SERVICE_CONFIG_UPDATE,
        Permissions.SERVICE_CONFIG_DELETE,
        Permissions.SERVICE_PERMISSIONS_READ,
        Permissions.SERVICE_PERMISSIONS_WRITE,
        Permissions.APIKEYS_READ,
        Permissions.APIKEYS_WRITE,
        Permissions.AUTH_ROLES_READ,
        Permissions.AUTH_ROLES_CREATE,
        Permissions.AUTH_ROLES_UPDATE,
        Permissions.AUTH_ROLES_DELETE
    })
    public Map<String, Object> whoami() {
        var result = new java.util.LinkedHashMap<String, Object>();

        var principal = identity.getPrincipal();
        if (principal instanceof ApiKeyPrincipal apiKeyPrincipal) {
            result.put("keyId", apiKeyPrincipal.getKeyId());
            result.put("name", apiKeyPrincipal.getName());
        } else {
            result.put("name", principal.getName());
        }

        // Get permissions from identity attributes
        @SuppressWarnings("unchecked")
        var permissions = (Set<String>) identity.getAttribute("permissions");
        if (permissions != null) {
            result.put("permissions", permissions);
        }

        // Include roles for debugging/transparency
        result.put("roles", identity.getRoles());

        // Include expiration if present
        var expiresAt = identity.<Instant>getAttribute("expiresAt");
        if (expiresAt != null) {
            result.put("expiresAt", expiresAt.toString());
        }

        return result;
    }
}
