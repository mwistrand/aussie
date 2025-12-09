package aussie.adapter.in.rest;

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

import io.smallrye.mutiny.Uni;

import aussie.adapter.in.auth.PermissionRoleMapper;
import aussie.adapter.in.dto.ServiceRegistrationRequest;
import aussie.adapter.in.dto.ServiceRegistrationResponse;
import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.model.RegistrationResult;
import aussie.core.service.ServiceRegistry;

/**
 * REST resource for service administration.
 *
 * <p>This adapter handles HTTP-specific concerns (request/response mapping, status codes)
 * and delegates all business logic and validation to the service layer.
 *
 * <p>Authorization is enforced via {@code @RolesAllowed} annotations:
 * <ul>
 *   <li>GET endpoints require {@code admin-read} or {@code admin} role</li>
 *   <li>POST/DELETE endpoints require {@code admin-write} or {@code admin} role</li>
 * </ul>
 */
@Path("/admin/services")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

    private final ServiceRegistry serviceRegistry;

    @Inject
    public AdminResource(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    @POST
    @RolesAllowed({PermissionRoleMapper.ROLE_ADMIN_WRITE, PermissionRoleMapper.ROLE_ADMIN})
    public Uni<Response> registerService(ServiceRegistrationRequest request) {
        // Minimal adapter-level validation to prevent NPE during DTO conversion
        if (request == null || request.serviceId() == null || request.baseUrl() == null) {
            throw GatewayProblem.badRequest("serviceId and baseUrl are required");
        }

        try {
            var service = request.toModel();

            // Delegate to service for business validation and persistence
            return serviceRegistry.register(service).map(result -> switch (result) {
                case RegistrationResult.Success s -> Response.status(Response.Status.CREATED)
                        .entity(ServiceRegistrationResponse.fromModel(s.registration()))
                        .build();
                case RegistrationResult.Failure f -> throw GatewayProblem.badRequest(f.reason());
            });
        } catch (IllegalArgumentException e) {
            throw GatewayProblem.validationError(e.getMessage());
        }
    }

    @DELETE
    @Path("/{serviceId}")
    @RolesAllowed({PermissionRoleMapper.ROLE_ADMIN_WRITE, PermissionRoleMapper.ROLE_ADMIN})
    public Uni<Response> unregisterService(@PathParam("serviceId") String serviceId) {
        return serviceRegistry.getService(serviceId).flatMap(existing -> {
            if (existing.isEmpty()) {
                throw GatewayProblem.resourceNotFound("Service", serviceId);
            }

            return serviceRegistry.unregister(serviceId).map(deleted -> Response.noContent()
                    .build());
        });
    }

    @GET
    @RolesAllowed({PermissionRoleMapper.ROLE_ADMIN_READ, PermissionRoleMapper.ROLE_ADMIN})
    public Uni<List<ServiceRegistrationResponse>> listServices() {
        return serviceRegistry.getAllServices().map(services -> services.stream()
                .map(ServiceRegistrationResponse::fromModel)
                .toList());
    }

    @GET
    @Path("/{serviceId}")
    @RolesAllowed({PermissionRoleMapper.ROLE_ADMIN_READ, PermissionRoleMapper.ROLE_ADMIN})
    public Uni<Response> getService(@PathParam("serviceId") String serviceId) {
        return serviceRegistry.getService(serviceId).map(serviceOpt -> serviceOpt
                .map(service -> Response.ok(ServiceRegistrationResponse.fromModel(service))
                        .build())
                .orElseThrow(() -> GatewayProblem.resourceNotFound("Service", serviceId)));
    }
}
