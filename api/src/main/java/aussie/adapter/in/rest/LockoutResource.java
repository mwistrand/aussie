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

import io.quarkus.arc.properties.IfBuildProperty;
import io.quarkus.security.PermissionsAllowed;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.config.AuthRateLimitConfig;
import aussie.core.model.auth.Permission;
import aussie.core.service.auth.AuthRateLimitService;
import aussie.spi.FailedAttemptRepository.LockoutInfo;

/**
 * REST resource for authentication lockout administration.
 *
 * <p>
 * Provides endpoints for:
 * <ul>
 * <li>Listing current lockouts</li>
 * <li>Checking lockout status for specific IPs or identifiers</li>
 * <li>Clearing lockouts (unlock accounts)</li>
 * <li>Getting failed attempt counts</li>
 * </ul>
 *
 * <p>
 * These endpoints are intended for platform administrators to manage
 * brute force protection lockouts.
 */
@Path("/admin/lockouts")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@IfBuildProperty(name = "aussie.auth.rate-limit.enabled", stringValue = "true", enableIfMissing = true)
public class LockoutResource {

    private static final Logger LOG = Logger.getLogger(LockoutResource.class);

    private final AuthRateLimitService rateLimitService;
    private final AuthRateLimitConfig config;

    public LockoutResource(AuthRateLimitService rateLimitService, AuthRateLimitConfig config) {
        this.rateLimitService = rateLimitService;
        this.config = config;
    }

    /**
     * List all current lockouts.
     *
     * @param limit maximum number of entries to return
     * @return list of lockout info records
     */
    @GET
    @PermissionsAllowed({Permission.LOCKOUTS_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> listLockouts(@QueryParam("limit") Integer limit) {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Authentication rate limiting");
        }

        final var effectiveLimit = limit != null && limit > 0 ? limit : 100;

        return rateLimitService
                .streamAllLockouts()
                .select()
                .first(effectiveLimit)
                .collect()
                .asList()
                .map(lockouts -> {
                    final var response =
                            lockouts.stream().map(this::formatLockoutInfo).toList();
                    return Response.ok(Map.of(
                                    "lockouts", response,
                                    "count", response.size(),
                                    "limit", effectiveLimit))
                            .build();
                });
    }

    /**
     * Get lockout status for a specific IP address.
     *
     * @param ip the IP address to check
     * @return lockout status
     */
    @GET
    @Path("/ips/{ip}")
    @PermissionsAllowed({Permission.LOCKOUTS_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> getIpLockoutStatus(@PathParam("ip") String ip) {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Authentication rate limiting");
        }

        final var key = "ip:" + ip;
        return getLockoutStatus(key, "ip", ip);
    }

    /**
     * Get lockout status for a specific user identifier.
     *
     * @param identifier the username or email to check
     * @return lockout status
     */
    @GET
    @Path("/users/{identifier}")
    @PermissionsAllowed({Permission.LOCKOUTS_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> getUserLockoutStatus(@PathParam("identifier") String identifier) {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Authentication rate limiting");
        }

        final var key = "user:" + identifier;
        return getLockoutStatus(key, "user", identifier);
    }

    /**
     * Get lockout status for a specific API key prefix.
     *
     * @param keyPrefix the API key prefix to check
     * @return lockout status
     */
    @GET
    @Path("/apikeys/{keyPrefix}")
    @PermissionsAllowed({Permission.LOCKOUTS_READ_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> getApiKeyLockoutStatus(@PathParam("keyPrefix") String keyPrefix) {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Authentication rate limiting");
        }

        final var key = "apikey:" + keyPrefix;
        return getLockoutStatus(key, "apikey", keyPrefix);
    }

    private Uni<Response> getLockoutStatus(String key, String type, String value) {
        return Uni.combine()
                .all()
                .unis(
                        rateLimitService.isLockedOut(key),
                        rateLimitService.getFailedAttemptCount(key),
                        rateLimitService.getLockoutInfo(key))
                .with((lockedOut, attempts, info) -> {
                    final var response = new java.util.LinkedHashMap<String, Object>();
                    response.put("type", type);
                    response.put("value", value);
                    response.put("key", key);
                    response.put("lockedOut", lockedOut);
                    response.put("failedAttempts", attempts);
                    response.put("maxAttempts", config.maxFailedAttempts());

                    if (info != null) {
                        response.put("lockoutStarted", info.lockedAt().toString());
                        response.put("lockoutExpires", info.expiresAt().toString());
                        response.put("lockoutCount", info.lockoutCount());
                    }

                    response.put("checkedAt", Instant.now().toString());

                    return Response.ok(response).build();
                });
    }

    /**
     * Clear lockout for an IP address.
     *
     * @param ip      the IP address to unlock
     * @param request optional request body with reason
     * @return 204 No Content on success
     */
    @DELETE
    @Path("/ips/{ip}")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermissionsAllowed({Permission.LOCKOUTS_WRITE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> clearIpLockout(@PathParam("ip") String ip, ClearLockoutRequest request) {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Authentication rate limiting");
        }

        final var reason = request != null ? request.reason() : null;
        LOG.infof("Clearing IP lockout: ip=%s, reason=%s", ip, reason);

        return rateLimitService.clearIpLockout(ip).map(v -> {
            LOG.infof("IP lockout cleared: ip=%s", ip);
            return Response.noContent().build();
        });
    }

    /**
     * Clear lockout for a user identifier.
     *
     * @param identifier the username or email to unlock
     * @param request    optional request body with reason
     * @return 204 No Content on success
     */
    @DELETE
    @Path("/users/{identifier}")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermissionsAllowed({Permission.LOCKOUTS_WRITE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> clearUserLockout(@PathParam("identifier") String identifier, ClearLockoutRequest request) {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Authentication rate limiting");
        }

        final var reason = request != null ? request.reason() : null;
        LOG.infof("Clearing user lockout: identifier=%s, reason=%s", identifier, reason);

        return rateLimitService.clearUserLockout(identifier).map(v -> {
            LOG.infof("User lockout cleared: identifier=%s", identifier);
            return Response.noContent().build();
        });
    }

    /**
     * Clear lockout for an API key prefix.
     *
     * @param keyPrefix the API key prefix to unlock
     * @param request   optional request body with reason
     * @return 204 No Content on success
     */
    @DELETE
    @Path("/apikeys/{keyPrefix}")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermissionsAllowed({Permission.LOCKOUTS_WRITE_VALUE, Permission.ADMIN_VALUE})
    public Uni<Response> clearApiKeyLockout(@PathParam("keyPrefix") String keyPrefix, ClearLockoutRequest request) {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Authentication rate limiting");
        }

        final var reason = request != null ? request.reason() : null;
        LOG.infof("Clearing API key lockout: keyPrefix=%s, reason=%s", keyPrefix, reason);

        return rateLimitService.clearLockout("apikey:" + keyPrefix).map(v -> {
            LOG.infof("API key lockout cleared: keyPrefix=%s", keyPrefix);
            return Response.noContent().build();
        });
    }

    /**
     * Clear all lockouts (emergency use only).
     *
     * <p>
     * This is a dangerous operation that clears all lockouts. It should only
     * be used in emergency situations where legitimate users are being blocked.
     *
     * @param request request body with force flag
     * @return result with number of lockouts cleared
     */
    @POST
    @Path(":reset")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermissionsAllowed({Permission.ADMIN_VALUE})
    public Uni<Response> clearAllLockouts(ClearAllLockoutsRequest request) {
        if (!config.enabled()) {
            throw GatewayProblem.featureDisabled("Authentication rate limiting");
        }

        if (request == null || !request.force()) {
            throw GatewayProblem.badRequest("Must set force=true to clear all lockouts");
        }

        final var reason = request.reason();
        LOG.warnf("Clearing ALL lockouts: reason=%s", reason);

        return rateLimitService
                .streamAllLockouts()
                .onItem()
                .call(info -> rateLimitService.clearLockout(info.key()))
                .collect()
                .asList()
                .map(cleared -> {
                    LOG.warnf("Cleared %d lockouts", cleared.size());
                    return Response.ok(Map.of(
                                    "status",
                                    "cleared",
                                    "count",
                                    cleared.size(),
                                    "clearedAt",
                                    Instant.now().toString()))
                            .build();
                });
    }

    private Map<String, Object> formatLockoutInfo(LockoutInfo info) {
        final var parts = info.key().split(":", 2);
        final var type = parts.length > 0 ? parts[0] : "unknown";
        final var value = parts.length > 1 ? parts[1] : info.key();

        return Map.of(
                "key", info.key(),
                "type", type,
                "value", value,
                "lockedAt", info.lockedAt().toString(),
                "expiresAt", info.expiresAt().toString(),
                "reason", info.reason() != null ? info.reason() : "max_failed_attempts",
                "failedAttempts", info.failedAttempts(),
                "lockoutCount", info.lockoutCount());
    }

    /**
     * Request body for clearing a lockout.
     */
    public record ClearLockoutRequest(String reason) {}

    /**
     * Request body for clearing all lockouts.
     */
    public record ClearAllLockoutsRequest(boolean force, String reason) {}
}
