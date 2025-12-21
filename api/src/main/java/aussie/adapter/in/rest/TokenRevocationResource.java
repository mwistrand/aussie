package aussie.adapter.in.rest;

import java.time.Instant;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
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
import org.jboss.logging.Logger;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.MalformedClaimException;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.config.TokenRevocationConfig;
import aussie.core.model.auth.Permission;
import aussie.core.service.auth.TokenRevocationService;

/**
 * REST resource for token revocation administration.
 *
 * <p>Provides endpoints for:
 * <ul>
 *   <li>Revoking specific tokens by JTI</li>
 *   <li>Revoking all tokens for a user (logout everywhere)</li>
 *   <li>Listing currently revoked tokens (for debugging)</li>
 *   <li>Checking revocation status of a specific token</li>
 *   <li>Triggering bloom filter rebuild</li>
 * </ul>
 */
@Path("/admin/tokens")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class TokenRevocationResource {

    private static final Logger LOG = Logger.getLogger(TokenRevocationResource.class);

    private final TokenRevocationService revocationService;
    private final TokenRevocationConfig config;

    public TokenRevocationResource(TokenRevocationService revocationService, TokenRevocationConfig config) {
        this.revocationService = revocationService;
        this.config = config;
    }

    /**
     * Revoke a specific token by JTI.
     *
     * @param jti the JWT ID to revoke
     * @param request optional request body with reason and expiry
     * @return 204 No Content on success
     */
    @DELETE
    @Path("/{jti}")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermissionsAllowed({Permission.TOKENS_REVOKE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> revokeToken(@PathParam("jti") String jti, RevokeTokenRequest request) {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Token revocation");
        }

        if (jti == null || jti.isBlank()) {
            throw GatewayProblem.badRequest("JTI is required");
        }

        var expiresAt = request != null && request.expiresAt() != null ? request.expiresAt() : null;

        var reason = request != null ? request.reason() : null;
        LOG.infof("Revoking token: jti=%s, reason=%s", jti, reason);

        return revocationService.revokeToken(jti, expiresAt).map(v -> {
            LOG.infof("Token revoked: jti=%s", jti);
            return Response.noContent().build();
        });
    }

    /**
     * Revoke a token by providing the full JWT.
     *
     * <p>This endpoint extracts the JTI claim from the provided token and revokes it.
     * This is useful when you have the full token but not the JTI value.
     *
     * @param request the request containing the full JWT token
     * @return 200 OK with the extracted JTI on success
     */
    @POST
    @Path("/revoke")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermissionsAllowed({Permission.TOKENS_REVOKE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> revokeByToken(RevokeByTokenRequest request) {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Token revocation");
        }

        if (request == null || request.token() == null || request.token().isBlank()) {
            throw GatewayProblem.badRequest("Token is required");
        }

        // Extract JTI from token without signature verification
        // (we're revoking it, so we don't care if it's valid)
        String jti;
        Instant expiresAt;
        try {
            var claims = extractClaimsWithoutValidation(request.token());
            jti = claims.getJwtId();
            if (jti == null || jti.isBlank()) {
                throw GatewayProblem.badRequest("Token does not contain a JTI claim");
            }
            expiresAt = claims.getExpirationTime() != null
                    ? Instant.ofEpochSecond(claims.getExpirationTime().getValue())
                    : null;
        } catch (InvalidJwtException | MalformedClaimException e) {
            LOG.warnf("Failed to parse token for revocation: %s", e.getMessage());
            throw GatewayProblem.badRequest("Invalid token format: " + e.getMessage());
        }

        var reason = request.reason();
        LOG.infof("Revoking token by JWT: jti=%s, reason=%s", jti, reason);

        final var extractedJti = jti;
        return revocationService.revokeToken(jti, expiresAt).map(v -> {
            LOG.infof("Token revoked: jti=%s", extractedJti);
            return Response.ok(Map.of(
                            "jti",
                            extractedJti,
                            "status",
                            "revoked",
                            "revokedAt",
                            Instant.now().toString()))
                    .build();
        });
    }

    private JwtClaims extractClaimsWithoutValidation(String token) throws InvalidJwtException {
        // Build a consumer that skips all validation - we just want to read claims
        var consumer = new JwtConsumerBuilder()
                .setSkipSignatureVerification()
                .setSkipAllValidators()
                .setDisableRequireSignature()
                .setSkipAllDefaultValidators()
                .build();
        return consumer.processToClaims(token);
    }

    /**
     * Revoke all tokens for a user (logout everywhere).
     *
     * @param userId the user ID whose tokens should be revoked
     * @param request optional request body with reason
     * @return 204 No Content on success
     */
    @DELETE
    @Path("/users/{userId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermissionsAllowed({Permission.TOKENS_REVOKE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> revokeUserTokens(@PathParam("userId") String userId, RevokeUserTokensRequest request) {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Token revocation");
        }

        if (!config.checkUserRevocation()) {
            throw GatewayProblem.featureDisabled("User-level token revocation");
        }

        if (userId == null || userId.isBlank()) {
            throw GatewayProblem.badRequest("User ID is required");
        }

        var reason = request != null ? request.reason() : null;
        LOG.infof("Revoking all tokens for user: userId=%s, reason=%s", userId, reason);

        return revocationService.revokeAllUserTokens(userId).map(v -> {
            LOG.infof("All tokens revoked for user: userId=%s", userId);
            return Response.noContent().build();
        });
    }

    /**
     * Check if a specific token is revoked.
     *
     * @param jti the JWT ID to check
     * @return revocation status
     */
    @GET
    @Path("/{jti}/status")
    @PermissionsAllowed({Permission.TOKENS_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> checkRevocationStatus(@PathParam("jti") String jti) {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Token revocation");
        }

        if (jti == null || jti.isBlank()) {
            throw GatewayProblem.badRequest("JTI is required");
        }

        return revocationService.isTokenRevoked(jti).map(revoked -> Response.ok(Map.of(
                        "jti", jti,
                        "revoked", revoked,
                        "checkedAt", Instant.now().toString()))
                .build());
    }

    /**
     * List all currently revoked tokens (for debugging).
     *
     * <p>Note: This can be expensive for large revocation lists.
     *
     * @param limit maximum number of entries to return
     * @return list of revoked JTIs
     */
    @GET
    @PermissionsAllowed({Permission.TOKENS_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> listRevokedTokens(@QueryParam("limit") Integer limit) {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Token revocation");
        }

        var effectiveLimit = limit != null && limit > 0 ? limit : 100;

        return revocationService
                .streamAllRevokedJtis()
                .select()
                .first(effectiveLimit)
                .collect()
                .asList()
                .map(jtis -> Response.ok(Map.of("revokedTokens", jtis, "count", jtis.size(), "limit", effectiveLimit))
                        .build());
    }

    /**
     * List all users with blanket revocations.
     *
     * @param limit maximum number of entries to return
     * @return list of user IDs with active revocations
     */
    @GET
    @Path("/users")
    @PermissionsAllowed({Permission.TOKENS_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> listRevokedUsers(@QueryParam("limit") Integer limit) {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Token revocation");
        }

        var effectiveLimit = limit != null && limit > 0 ? limit : 100;

        return revocationService
                .streamAllRevokedUsers()
                .select()
                .first(effectiveLimit)
                .collect()
                .asList()
                .map(users -> Response.ok(Map.of("revokedUsers", users, "count", users.size(), "limit", effectiveLimit))
                        .build());
    }

    /**
     * Inspect a token to view its claims without validation.
     *
     * <p>This endpoint decodes a JWT and returns its claims, including the JTI.
     * Useful for admins who need to find the JTI for revocation or debugging.
     * No signature validation is performed.
     *
     * @param request the request containing the full JWT token
     * @return the decoded claims
     */
    @POST
    @Path("/inspect")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermissionsAllowed({Permission.TOKENS_READ_VALUE, Permission.ADMIN_VALUE})
    public Response inspectToken(InspectTokenRequest request) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            throw GatewayProblem.badRequest("Token is required");
        }

        try {
            var claims = extractClaimsWithoutValidation(request.token());

            // Build response with key claims highlighted
            var response = new java.util.LinkedHashMap<String, Object>();
            response.put("jti", claims.getJwtId());
            response.put("subject", claims.getSubject());
            response.put("issuer", claims.getIssuer());
            response.put("audience", claims.getAudience());
            response.put(
                    "issuedAt",
                    claims.getIssuedAt() != null
                            ? Instant.ofEpochSecond(claims.getIssuedAt().getValue())
                                    .toString()
                            : null);
            response.put(
                    "expiresAt",
                    claims.getExpirationTime() != null
                            ? Instant.ofEpochSecond(claims.getExpirationTime().getValue())
                                    .toString()
                            : null);
            response.put(
                    "notBefore",
                    claims.getNotBefore() != null
                            ? Instant.ofEpochSecond(claims.getNotBefore().getValue())
                                    .toString()
                            : null);

            // Include all other claims
            var otherClaims = new java.util.HashMap<>(claims.getClaimsMap());
            otherClaims.remove("jti");
            otherClaims.remove("sub");
            otherClaims.remove("iss");
            otherClaims.remove("aud");
            otherClaims.remove("iat");
            otherClaims.remove("exp");
            otherClaims.remove("nbf");
            if (!otherClaims.isEmpty()) {
                response.put("otherClaims", otherClaims);
            }

            return Response.ok(response).build();
        } catch (InvalidJwtException | MalformedClaimException e) {
            LOG.warnf("Failed to parse token for inspection: %s", e.getMessage());
            throw GatewayProblem.badRequest("Invalid token format: " + e.getMessage());
        }
    }

    /**
     * Force rebuild of the bloom filter from the remote store.
     *
     * <p>This is useful after manual manipulation of the revocation store
     * or to recover from bloom filter desync.
     *
     * @return rebuild status
     */
    @POST
    @Path("/bloom-filter/rebuild")
    @PermissionsAllowed({Permission.ADMIN_VALUE})
    public Uni<Response> rebuildBloomFilter() {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Token revocation");
        }

        LOG.info("Triggering bloom filter rebuild");

        return revocationService.rebuildBloomFilter().map(v -> {
            LOG.info("Bloom filter rebuild completed");
            return Response.ok(Map.of(
                            "status", "rebuilt", "rebuiltAt", Instant.now().toString()))
                    .build();
        });
    }

    /**
     * Request body for revoking a specific token.
     */
    public record RevokeTokenRequest(String reason, Instant expiresAt) {}

    /**
     * Request body for revoking a token by providing the full JWT.
     */
    public record RevokeByTokenRequest(String token, String reason) {}

    /**
     * Request body for inspecting a token.
     */
    public record InspectTokenRequest(String token) {}

    /**
     * Request body for revoking all user tokens.
     */
    public record RevokeUserTokensRequest(String reason) {}
}
