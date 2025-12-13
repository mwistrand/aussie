package aussie.adapter.in.rest;

import java.util.List;
import java.util.Set;

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

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.smallrye.mutiny.Uni;

import aussie.adapter.in.dto.ServiceRegistrationRequest;
import aussie.adapter.in.dto.ServiceRegistrationResponse;
import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.model.Permission;
import aussie.core.model.RegistrationResult;
import aussie.core.service.ServiceRegistry;

/**
 * REST resource for service administration.
 *
 * <p>
 * This adapter handles HTTP-specific concerns (request/response mapping, status
 * codes) and delegates all business logic, validation, and authorization to
 * {@link ServiceRegistry}.
 */
@Path("/admin/services")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

    private final ServiceRegistry serviceRegistry;
    private final CurrentIdentityAssociation identityAssociation;

    @Inject
    public AdminResource(ServiceRegistry serviceRegistry, CurrentIdentityAssociation identityAssociation) {
        this.serviceRegistry = serviceRegistry;
        this.identityAssociation = identityAssociation;
    }

    @POST
    @RolesAllowed({Permission.SERVICE_CONFIG_CREATE, Permission.SERVICE_CONFIG_UPDATE, Permission.ADMIN})
    public Uni<Response> registerService(ServiceRegistrationRequest request) {
        // Minimal adapter-level validation to prevent NPE during DTO conversion
        if (request == null || request.serviceId() == null || request.baseUrl() == null) {
            throw GatewayProblem.badRequest("serviceId and baseUrl are required");
        }

        try {
            var service = request.toModel();

            return identityAssociation.getDeferredIdentity().flatMap(identity -> {
                var permissions = extractPermissions(identity);

                // Delegate to service for validation, authorization, and persistence
                return serviceRegistry.register(service, permissions).map(result -> switch (result) {
                    case RegistrationResult.Success s -> Response.status(Response.Status.CREATED)
                            .entity(ServiceRegistrationResponse.fromModel(s.registration()))
                            .build();
                    case RegistrationResult.Failure f -> throw toGatewayProblem(f);
                });
            });
        } catch (IllegalArgumentException e) {
            throw GatewayProblem.validationError(e.getMessage());
        }
    }

    @DELETE
    @Path("/{serviceId}")
    @RolesAllowed({Permission.SERVICE_CONFIG_DELETE, Permission.ADMIN})
    public Uni<Response> unregisterService(@PathParam("serviceId") String serviceId) {
        return identityAssociation.getDeferredIdentity().flatMap(identity -> {
            var permissions = extractPermissions(identity);

            return serviceRegistry.unregisterAuthorized(serviceId, permissions).map(result -> switch (result) {
                case RegistrationResult.Success s -> Response.noContent().build();
                case RegistrationResult.Failure f -> throw toGatewayProblem(f);
            });
        });
    }

    @GET
    @RolesAllowed({Permission.SERVICE_CONFIG_READ, Permission.ADMIN})
    public Uni<List<ServiceRegistrationResponse>> listServices() {
        // List all services - service-level filtering could be added here if needed
        return serviceRegistry.getAllServices().map(services -> services.stream()
                .map(ServiceRegistrationResponse::fromModel)
                .toList());
    }

    @GET
    @Path("/{serviceId}")
    @RolesAllowed({Permission.SERVICE_CONFIG_READ, Permission.ADMIN})
    public Uni<Response> getService(@PathParam("serviceId") String serviceId) {
        return identityAssociation.getDeferredIdentity().flatMap(identity -> {
            var permissions = extractPermissions(identity);

            return serviceRegistry.getServiceAuthorized(serviceId, permissions).map(result -> switch (result) {
                case RegistrationResult.Success s -> Response.ok(
                                ServiceRegistrationResponse.fromModel(s.registration()))
                        .build();
                case RegistrationResult.Failure f -> throw toGatewayProblem(f);
            });
        });
    }

    /**
     * Extracts effective permissions from the identity for service-level
     * authorization.
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
     * Converts a RegistrationResult.Failure to the appropriate HttpProblem
     * exception.
     */
    private HttpProblem toGatewayProblem(RegistrationResult.Failure failure) {
        return switch (failure.statusCode()) {
            case 403 -> GatewayProblem.forbidden(failure.reason());
            case 404 -> GatewayProblem.resourceNotFound("Service", failure.reason());
            case 409 -> GatewayProblem.conflict(failure.reason());
            default -> GatewayProblem.badRequest(failure.reason());
        };
    }
}
