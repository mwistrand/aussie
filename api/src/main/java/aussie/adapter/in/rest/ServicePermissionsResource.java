package aussie.adapter.in.rest;

import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkus.security.PermissionsAllowed;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.smallrye.mutiny.Uni;

import aussie.adapter.in.dto.ServicePermissionPolicyDto;
import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.model.auth.Permissions;
import aussie.core.service.auth.ServiceAuthorizationService;
import aussie.core.service.routing.ServiceRegistry;

/**
 * REST resource for managing service permission policies.
 *
 * <p>
 * Permission policies define which permissions are allowed to perform specific
 * operations on a service. This resource allows reading and updating these
 * policies.
 *
 * <p>
 * Optimistic locking is enforced via the If-Match header to prevent concurrent
 * update conflicts.
 */
@Path("/admin/services/{serviceId}/permissions")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServicePermissionsResource {

    private final ServiceRegistry serviceRegistry;
    private final ServiceAuthorizationService authService;
    private final CurrentIdentityAssociation identityAssociation;

    @Inject
    public ServicePermissionsResource(
            ServiceRegistry serviceRegistry,
            ServiceAuthorizationService authService,
            CurrentIdentityAssociation identityAssociation) {
        this.serviceRegistry = serviceRegistry;
        this.authService = authService;
        this.identityAssociation = identityAssociation;
    }

    /**
     * Get the permission policy for a service.
     */
    @GET
    @PermissionsAllowed({Permissions.SERVICE_PERMISSIONS_READ, Permissions.ADMIN})
    public Uni<Response> getPermissions(@PathParam("serviceId") String serviceId) {
        return identityAssociation.getDeferredIdentity().flatMap(identity -> {
            var permissions = extractPermissions(identity);

            return serviceRegistry.getService(serviceId).map(serviceOpt -> {
                if (serviceOpt.isEmpty()) {
                    throw GatewayProblem.resourceNotFound("Service", serviceId);
                }

                var service = serviceOpt.get();

                // Check authorization for reading permissions
                if (!authService.isAuthorizedForService(service, Permissions.PERMISSIONS_READ, permissions)) {
                    throw GatewayProblem.forbidden("Not authorized to read permissions for service: " + serviceId);
                }

                var policyDto = service.permissionPolicy()
                        .map(ServicePermissionPolicyDto::fromModel)
                        .orElse(null);

                return Response.ok(new PermissionPolicyResponse(policyDto, service.version()))
                        .build();
            });
        });
    }

    /**
     * Update the permission policy for a service.
     *
     * <p>
     * Requires If-Match header with the current version for optimistic locking.
     */
    @PUT
    @PermissionsAllowed({Permissions.SERVICE_PERMISSIONS_WRITE, Permissions.ADMIN})
    public Uni<Response> updatePermissions(
            @PathParam("serviceId") String serviceId,
            @HeaderParam("If-Match") Long ifMatch,
            ServicePermissionPolicyDto policyDto) {

        if (ifMatch == null) {
            throw GatewayProblem.badRequest("If-Match header is required for permission updates");
        }

        return identityAssociation.getDeferredIdentity().flatMap(identity -> {
            var permissions = extractPermissions(identity);

            return serviceRegistry.getService(serviceId).flatMap(serviceOpt -> {
                if (serviceOpt.isEmpty()) {
                    throw GatewayProblem.resourceNotFound("Service", serviceId);
                }

                var service = serviceOpt.get();

                // Check authorization for updating permissions
                if (!authService.isAuthorizedForService(service, Permissions.PERMISSIONS_WRITE, permissions)) {
                    throw GatewayProblem.forbidden("Not authorized to update permissions for service: " + serviceId);
                }

                // Check version for optimistic locking
                if (service.version() != ifMatch) {
                    throw GatewayProblem.conflict("Version mismatch: expected %d, got %d. Reload and try again."
                            .formatted(service.version(), ifMatch));
                }

                // Update the service with new policy and incremented version
                var newPolicy = policyDto != null ? policyDto.toModel() : null;
                var updatedService = service.withPermissionPolicy(newPolicy).withIncrementedVersion();

                return serviceRegistry.update(updatedService).map(v -> {
                    var responsePolicyDto = updatedService
                            .permissionPolicy()
                            .map(ServicePermissionPolicyDto::fromModel)
                            .orElse(null);

                    return Response.ok(new PermissionPolicyResponse(responsePolicyDto, updatedService.version()))
                            .header("ETag", updatedService.version())
                            .build();
                });
            });
        });
    }

    /**
     * Extract effective permissions from the identity for service-level authorization.
     *
     * <p>
     * Permissions are computed by
     * {@link aussie.adapter.in.auth.ApiKeyIdentityProvider#buildEffectivePermissions}.
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractPermissions(io.quarkus.security.identity.SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return Set.of();
        }

        var permissions = identity.getAttribute("permissions");
        if (permissions instanceof Set) {
            return (Set<String>) permissions;
        }

        return Set.of();
    }

    /**
     * Response DTO for permission policy operations.
     */
    public record PermissionPolicyResponse(ServicePermissionPolicyDto permissionPolicy, long version) {}
}
