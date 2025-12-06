package aussie.adapter.in.rest;

import java.util.List;
import java.util.Map;

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
            return Uni.createFrom()
                    .item(Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", "serviceId and baseUrl are required"))
                            .build());
        }

        try {
            var service = request.toModel();

            // Delegate to service for business validation and persistence
            return serviceRegistry.register(service).map(result -> switch (result) {
                case RegistrationResult.Success s -> Response.status(Response.Status.CREATED)
                        .entity(ServiceRegistrationResponse.fromModel(s.registration()))
                        .build();
                case RegistrationResult.Failure f -> Response.status(f.statusCode())
                        .entity(Map.of("error", f.reason()))
                        .build();
            });
        } catch (IllegalArgumentException e) {
            return Uni.createFrom()
                    .item(Response.status(Response.Status.BAD_REQUEST)
                            .entity(Map.of("error", e.getMessage()))
                            .build());
        }
    }

    @DELETE
    @Path("/{serviceId}")
    @RolesAllowed({PermissionRoleMapper.ROLE_ADMIN_WRITE, PermissionRoleMapper.ROLE_ADMIN})
    public Uni<Response> unregisterService(@PathParam("serviceId") String serviceId) {
        return serviceRegistry.getService(serviceId).flatMap(existing -> {
            if (existing.isEmpty()) {
                return Uni.createFrom()
                        .item(Response.status(Response.Status.NOT_FOUND)
                                .entity(Map.of("error", "Service not found: " + serviceId))
                                .build());
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
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Service not found: " + serviceId))
                        .build()));
    }
}
