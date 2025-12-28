package aussie.adapter.in.rest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkus.security.PermissionsAllowed;
import io.smallrye.mutiny.Uni;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.config.KeyRotationConfig;
import aussie.core.model.auth.KeyStatus;
import aussie.core.model.auth.Permission;
import aussie.core.model.auth.SigningKeyRecord;
import aussie.core.service.auth.KeyRotationService;
import aussie.core.service.auth.SigningKeyRegistry;

/**
 * REST resource for signing key administration.
 *
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Listing all signing keys and their statuses</li>
 *   <li>Viewing details of a specific key</li>
 *   <li>Triggering manual key rotation</li>
 *   <li>Deprecating or retiring keys</li>
 * </ul>
 */
@Path("/admin/keys")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SigningKeyResource {

    private final SigningKeyRegistry keyRegistry;
    private final KeyRotationService keyRotationService;
    private final KeyRotationConfig keyRotationConfig;

    @Inject
    public SigningKeyResource(
            SigningKeyRegistry keyRegistry,
            KeyRotationService keyRotationService,
            KeyRotationConfig keyRotationConfig) {
        this.keyRegistry = keyRegistry;
        this.keyRotationService = keyRotationService;
        this.keyRotationConfig = keyRotationConfig;
    }

    /**
     * List all signing keys.
     *
     * @return List of all keys with their metadata (private keys excluded)
     */
    @GET
    @PermissionsAllowed({Permission.KEYS_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<List<KeySummaryResponse>> listKeys() {
        ensureKeyRotationEnabled();

        return keyRotationService
                .listAllKeys()
                .map(keys -> keys.stream().map(KeySummaryResponse::from).toList());
    }

    /**
     * Get details of a specific key.
     *
     * @param keyId The key identifier
     * @param includePublicKey Whether to include the public key in the response
     * @return Key details (private key excluded)
     */
    @GET
    @Path("/{keyId}")
    @PermissionsAllowed({Permission.KEYS_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<KeyDetailResponse> getKey(
            @PathParam("keyId") String keyId, @QueryParam("includePublicKey") boolean includePublicKey) {
        ensureKeyRotationEnabled();

        return keyRotationService
                .getKey(keyId)
                .map(key -> KeyDetailResponse.from(key, includePublicKey))
                .onFailure(KeyRotationService.KeyNotFoundException.class)
                .transform(e -> GatewayProblem.resourceNotFound("Key", keyId));
    }

    /**
     * Trigger immediate key rotation.
     *
     * <p>Generates a new key and immediately activates it.
     * The current active key is deprecated.
     *
     * @param request Optional rotation request with reason
     * @return The new active key
     */
    @POST
    @Path("/rotate")
    @PermissionsAllowed({Permission.KEYS_ROTATE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> rotateKeys(RotateKeyRequest request) {
        ensureKeyRotationEnabled();

        final var reason =
                request != null && request.reason() != null ? request.reason() : "Manual rotation via admin API";

        return keyRotationService.triggerRotation(reason).map(newKey -> Response.ok(KeySummaryResponse.from(newKey))
                .status(Response.Status.CREATED)
                .build());
    }

    /**
     * Deprecate a key (stop signing, continue verifying).
     *
     * @param keyId The key identifier to deprecate
     * @return 204 No Content on success
     */
    @POST
    @Path("/{keyId}/deprecate")
    @PermissionsAllowed({Permission.KEYS_WRITE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> deprecateKey(@PathParam("keyId") String keyId) {
        ensureKeyRotationEnabled();

        return keyRotationService
                .forceDeprecate(keyId)
                .map(v -> Response.noContent().build())
                .onFailure(KeyRotationService.KeyNotFoundException.class)
                .transform(e -> GatewayProblem.resourceNotFound("Key", keyId));
    }

    /**
     * Retire a key (stop all usage).
     *
     * <p><strong>Warning:</strong> This immediately invalidates all tokens
     * signed with this key. Users will need to re-authenticate.
     *
     * @param keyId The key identifier to retire
     * @param force Must be true to confirm retirement
     * @return 204 No Content on success
     */
    @DELETE
    @Path("/{keyId}")
    @PermissionsAllowed({Permission.KEYS_WRITE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> retireKey(@PathParam("keyId") String keyId, @QueryParam("force") boolean force) {
        ensureKeyRotationEnabled();

        if (!force) {
            throw GatewayProblem.badRequest(
                    "Retiring a key invalidates all tokens signed with it. " + "Add ?force=true to confirm.");
        }

        return keyRotationService
                .forceRetire(keyId)
                .map(v -> Response.noContent().build())
                .onFailure(KeyRotationService.KeyNotFoundException.class)
                .transform(e -> GatewayProblem.resourceNotFound("Key", keyId));
    }

    /**
     * Get signing key registry health status.
     *
     * @return Health status including active key and cache info
     */
    @GET
    @Path("/health")
    @PermissionsAllowed({Permission.KEYS_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<HealthResponse> getHealth() {
        if (!keyRotationConfig.enabled()) {
            return Uni.createFrom().item(new HealthResponse(false, "disabled", null, 0, null));
        }

        return keyRotationService.listAllKeys().map(keys -> {
            final var activeKey = keys.stream()
                    .filter(k -> k.status() == KeyStatus.ACTIVE)
                    .findFirst()
                    .map(SigningKeyRecord::keyId)
                    .orElse(null);

            final var verificationKeyCount = (int) keys.stream()
                    .filter(k -> k.status() == KeyStatus.ACTIVE || k.status() == KeyStatus.DEPRECATED)
                    .count();

            final var lastRefresh = keyRegistry.getLastRefreshTime().orElse(null);
            final var status = keyRegistry.isReady() ? "healthy" : "initializing";

            return new HealthResponse(true, status, activeKey, verificationKeyCount, lastRefresh);
        });
    }

    private void ensureKeyRotationEnabled() {
        if (!keyRotationConfig.enabled()) {
            throw GatewayProblem.featureDisabled("Key Rotation");
        }
    }

    // ========================================================================
    // Request/Response DTOs
    // ========================================================================

    public record RotateKeyRequest(String reason) {}

    public record KeySummaryResponse(
            String keyId,
            KeyStatus status,
            Instant createdAt,
            Instant activatedAt,
            Instant deprecatedAt,
            Instant retiredAt) {
        public static KeySummaryResponse from(SigningKeyRecord key) {
            return new KeySummaryResponse(
                    key.keyId(), key.status(), key.createdAt(), key.activatedAt(), key.deprecatedAt(), key.retiredAt());
        }
    }

    public record KeyDetailResponse(
            String keyId,
            KeyStatus status,
            Instant createdAt,
            Instant activatedAt,
            Instant deprecatedAt,
            Instant retiredAt,
            boolean canSign,
            boolean canVerify,
            Map<String, Object> publicKey) {
        public static KeyDetailResponse from(SigningKeyRecord key, boolean includePublicKey) {
            Map<String, Object> publicKeyInfo = null;
            if (includePublicKey && key.publicKey() != null) {
                publicKeyInfo = Map.of(
                        "algorithm", key.publicKey().getAlgorithm(),
                        "format", key.publicKey().getFormat(),
                        "modulus_bits", key.publicKey().getModulus().bitLength());
            }

            return new KeyDetailResponse(
                    key.keyId(),
                    key.status(),
                    key.createdAt(),
                    key.activatedAt(),
                    key.deprecatedAt(),
                    key.retiredAt(),
                    key.canSign(),
                    key.canVerify(),
                    publicKeyInfo);
        }
    }

    public record HealthResponse(
            boolean enabled, String status, String activeKeyId, int verificationKeyCount, Instant lastCacheRefresh) {}
}
