package aussie.adapter.in.rest;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;

import aussie.adapter.in.auth.ApiKeyIdentityProvider.ApiKeyPrincipal;
import aussie.adapter.in.auth.PermissionRoleMapper;
import aussie.adapter.in.dto.CreateApiKeyRequest;
import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.model.ApiKey;
import aussie.core.port.in.ApiKeyManagement;

/**
 * REST resource for API key management.
 *
 * <p>Provides endpoints for creating, listing, and revoking API keys used
 * for authentication to admin endpoints.
 *
 * <p>Authorization is enforced via {@code @RolesAllowed} annotations:
 * <ul>
 *   <li>GET endpoints require {@code admin-read} or {@code admin} role</li>
 *   <li>POST/DELETE endpoints require {@code admin-write} or {@code admin} role</li>
 * </ul>
 */
@Path("/admin/api-keys")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ApiKeyResource {

    private final ApiKeyManagement apiKeyService;
    private final SecurityIdentity identity;

    @Inject
    public ApiKeyResource(ApiKeyManagement apiKeyService, SecurityIdentity identity) {
        this.apiKeyService = apiKeyService;
        this.identity = identity;
    }

    /**
     * Create a new API key.
     *
     * <p>The plaintext key is only returned in the response to this request.
     * It cannot be retrieved later - only the hash is stored.
     */
    @POST
    @RolesAllowed({PermissionRoleMapper.ROLE_ADMIN_WRITE, PermissionRoleMapper.ROLE_ADMIN})
    public Uni<Response> createKey(CreateApiKeyRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw GatewayProblem.badRequest("name is required");
        }

        Duration ttl = request.ttlDays() != null ? Duration.ofDays(request.ttlDays()) : null;

        // Get the creator's identity
        String createdBy = getCreatorId();

        return apiKeyService
                .create(request.name(), request.description(), request.permissions(), ttl, createdBy)
                .map(result -> {
                    // Return the plaintext key only this one time
                    var responseBody = new HashMap<String, Object>();
                    responseBody.put("keyId", result.keyId());
                    responseBody.put("key", result.plaintextKey());
                    responseBody.put("name", result.metadata().name());
                    responseBody.put("permissions", result.metadata().permissions());
                    responseBody.put("createdBy", result.metadata().createdBy());
                    if (result.metadata().expiresAt() != null) {
                        responseBody.put(
                                "expiresAt", result.metadata().expiresAt().toString());
                    }

                    return Response.status(Response.Status.CREATED)
                            .entity(responseBody)
                            .build();
                })
                .onFailure(IllegalArgumentException.class)
                .transform(e -> GatewayProblem.validationError(e.getMessage()));
    }

    /**
     * Gets the identifier of the principal creating this key.
     */
    private String getCreatorId() {
        var principal = identity.getPrincipal();
        if (principal instanceof ApiKeyPrincipal apiKeyPrincipal) {
            return apiKeyPrincipal.getKeyId();
        }
        return principal.getName();
    }

    /**
     * List all API keys.
     *
     * <p>Key hashes are redacted in the response.
     */
    @GET
    @RolesAllowed({PermissionRoleMapper.ROLE_ADMIN_READ, PermissionRoleMapper.ROLE_ADMIN})
    public Uni<List<ApiKey>> listKeys() {
        return apiKeyService.list();
    }

    /**
     * Get a specific API key by ID.
     *
     * <p>Key hash is redacted in the response.
     */
    @GET
    @Path("/{keyId}")
    @RolesAllowed({PermissionRoleMapper.ROLE_ADMIN_READ, PermissionRoleMapper.ROLE_ADMIN})
    public Uni<Response> getKey(@PathParam("keyId") String keyId) {
        return apiKeyService.get(keyId).map(opt -> opt.map(
                        key -> Response.ok(key).build())
                .orElseThrow(() -> GatewayProblem.resourceNotFound("API key", keyId)));
    }

    /**
     * Revoke an API key.
     *
     * <p>Revoked keys cannot be used for authentication. The key record is
     * retained for audit purposes.
     */
    @DELETE
    @Path("/{keyId}")
    @RolesAllowed({PermissionRoleMapper.ROLE_ADMIN_WRITE, PermissionRoleMapper.ROLE_ADMIN})
    public Uni<Response> revokeKey(@PathParam("keyId") String keyId) {
        return apiKeyService.revoke(keyId).map(revoked -> {
            if (revoked) {
                return Response.noContent().build();
            } else {
                throw GatewayProblem.resourceNotFound("API key", keyId);
            }
        });
    }
}
