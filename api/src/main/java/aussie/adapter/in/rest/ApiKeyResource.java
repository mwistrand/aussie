package aussie.adapter.in.rest;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkus.security.PermissionsAllowed;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;

import aussie.adapter.in.auth.ApiKeyIdentityProvider.ApiKeyPrincipal;
import aussie.adapter.in.dto.CreateApiKeyRequest;
import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.model.auth.ApiKey;
import aussie.core.model.auth.Permission;
import aussie.core.port.in.ApiKeyManagement;

/**
 * REST resource for API key management.
 *
 * <p>
 * Provides endpoints for creating, listing, and revoking API keys used
 * for authentication to admin endpoints.
 *
 * <p>
 * Authorization is enforced via {@code @PermissionsAllowed} annotations:
 * <ul>
 * <li>GET endpoints require {@code apikeys-read} or {@code admin} permission</li>
 * <li>POST/DELETE endpoints require {@code apikeys-write} or {@code admin}
 * permission</li>
 * </ul>
 */
@Path("/admin/api-keys")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ApiKeyResource {

    private final ApiKeyManagement apiKeyService;
    private final SecurityIdentity identity;
    private final RedisAdminMutationStore mutationStore;

    @Inject
    public ApiKeyResource(
            ApiKeyManagement apiKeyService, SecurityIdentity identity, RedisAdminMutationStore mutationStore) {
        this.apiKeyService = apiKeyService;
        this.identity = identity;
        this.mutationStore = mutationStore;
    }

    public ApiKeyResource(ApiKeyManagement apiKeyService, SecurityIdentity identity) {
        this(apiKeyService, identity, null);
    }

    /**
     * Create a new API key.
     *
     * <p>
     * The plaintext key is only returned in the response to this request.
     * It cannot be retrieved later - only the hash is stored.
     */
    @POST
    @PermissionsAllowed({Permission.APIKEYS_WRITE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> createKey(
            @Valid CreateApiKeyRequest request, @HeaderParam("Idempotency-Key") String idempotencyKey) {
        Duration ttl = request.ttlDays() != null ? Duration.ofDays(request.ttlDays()) : null;
        final var teamId = AdminMutationSupport.requireTeam(identity, request.teamId());

        // Get the creator's identity
        String createdBy = getCreatorId();

        return AdminMutationSupport.idempotent(
                mutationStore,
                identity,
                "api-key.create",
                idempotencyKey,
                new CreateFingerprint(request, teamId, ttl),
                () -> apiKeyService
                        .create(request.name(), request.description(), teamId, request.permissions(), ttl, createdBy)
                        .map(result -> {
                            AdminMutationSupport.audit(
                                    mutationStore, identity, "api-key.create", result.keyId(), "success");
                            // Return the plaintext key only this one time
                            var responseBody = new HashMap<String, Object>();
                            responseBody.put("keyId", result.keyId());
                            responseBody.put("key", result.plaintextKey());
                            responseBody.put("name", result.metadata().name());
                            responseBody.put("permissions", result.metadata().permissions());
                            responseBody.put("createdBy", result.metadata().createdBy());
                            if (result.metadata().teamId() != null) {
                                responseBody.put("teamId", result.metadata().teamId());
                            }
                            if (result.metadata().expiresAt() != null) {
                                responseBody.put(
                                        "expiresAt",
                                        result.metadata().expiresAt().toString());
                            }

                            return Response.status(Response.Status.CREATED)
                                    .entity(responseBody)
                                    .build();
                        }));
    }

    public Uni<Response> createKey(@Valid CreateApiKeyRequest request) {
        return createKey(request, null);
    }

    /**
     * Get the identifier of the principal creating this key.
     */
    private String getCreatorId() {
        var principal = identity.getPrincipal();
        if (principal instanceof ApiKeyPrincipal apiKeyPrincipal) {
            return apiKeyPrincipal.getKeyId();
        }
        return principal.getName();
    }

    /**
     * List API keys.
     *
     * <p>
     * Key hashes are redacted in the response.
     */
    @GET
    @PermissionsAllowed({Permission.APIKEYS_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<List<ApiKey>> listKeys(
            @QueryParam("limit") @DefaultValue("50") int limit, @QueryParam("offset") @DefaultValue("0") int offset) {
        AdminPagination.validate(limit, offset);
        var teamId = AdminMutationSupport.requireTeam(identity, null);
        return teamId == null ? apiKeyService.list(limit, offset) : apiKeyService.listForTeam(teamId, limit, offset);
    }

    /**
     * Get a specific API key by ID.
     *
     * <p>
     * Key hash is redacted in the response.
     */
    @GET
    @Path("/{keyId}")
    @PermissionsAllowed({Permission.APIKEYS_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> getKey(@PathParam("keyId") String keyId) {
        return apiKeyService.get(keyId).map(opt -> opt.filter(
                        key -> AdminMutationSupport.canSee(identity, key.teamId()))
                .map(key -> Response.ok(key).build())
                .orElseThrow(() -> GatewayProblem.resourceNotFound("API key", keyId)));
    }

    /**
     * Revoke an API key.
     *
     * <p>
     * Revoked keys cannot be used for authentication. The key record is
     * retained for audit purposes.
     */
    @DELETE
    @Path("/{keyId}")
    @PermissionsAllowed({Permission.APIKEYS_WRITE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> revokeKey(@PathParam("keyId") String keyId) {
        if (AdminMutationSupport.isGlobal(identity)) {
            return revoke(keyId);
        }
        return apiKeyService
                .get(keyId)
                .map(opt -> opt.filter(key -> AdminMutationSupport.canSee(identity, key.teamId()))
                        .orElseThrow(() -> GatewayProblem.resourceNotFound("API key", keyId)))
                .flatMap(key -> revoke(keyId));
    }

    private Uni<Response> revoke(String keyId) {
        return apiKeyService.revoke(keyId).map(revoked -> {
            if (!revoked) {
                throw GatewayProblem.resourceNotFound("API key", keyId);
            }
            AdminMutationSupport.audit(mutationStore, identity, "api-key.revoke", keyId, "success");
            return Response.noContent().build();
        });
    }

    private record CreateFingerprint(CreateApiKeyRequest request, String teamId, Duration ttl) {}
}
