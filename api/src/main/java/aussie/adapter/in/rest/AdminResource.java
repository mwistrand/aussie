package aussie.adapter.in.rest;

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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import aussie.adapter.in.dto.ServiceRegistrationRequest;
import aussie.adapter.in.dto.ServiceRegistrationResponse;
import aussie.core.model.ValidationResult;
import aussie.core.service.ServiceRegistrationValidator;
import aussie.core.service.ServiceRegistry;

@Path("/admin/services")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

    private final ServiceRegistry serviceRegistry;
    private final ServiceRegistrationValidator registrationValidator;

    @Inject
    public AdminResource(ServiceRegistry serviceRegistry, ServiceRegistrationValidator registrationValidator) {
        this.serviceRegistry = serviceRegistry;
        this.registrationValidator = registrationValidator;
    }

    @POST
    public Response registerService(ServiceRegistrationRequest request) {
        if (request == null || request.serviceId() == null || request.baseUrl() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "serviceId and baseUrl are required"))
                    .build();
        }

        try {
            var service = request.toModel();

            // Validate against gateway policies
            var validationResult = registrationValidator.validate(service);
            if (validationResult instanceof ValidationResult.Invalid invalid) {
                return Response.status(invalid.suggestedStatusCode())
                        .entity(Map.of("error", invalid.reason()))
                        .build();
            }

            serviceRegistry.register(service);
            return Response.status(Response.Status.CREATED)
                    .entity(ServiceRegistrationResponse.fromModel(service))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.getMessage()))
                    .build();
        }
    }

    @DELETE
    @Path("/{serviceId}")
    public Response unregisterService(@PathParam("serviceId") String serviceId) {
        var existing = serviceRegistry.getService(serviceId);
        if (existing.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Service not found: " + serviceId))
                    .build();
        }

        serviceRegistry.unregister(serviceId);
        return Response.noContent().build();
    }

    @GET
    public List<ServiceRegistrationResponse> listServices() {
        return serviceRegistry.getAllServices().stream()
                .map(ServiceRegistrationResponse::fromModel)
                .toList();
    }

    @GET
    @Path("/{serviceId}")
    public Response getService(@PathParam("serviceId") String serviceId) {
        return serviceRegistry
                .getService(serviceId)
                .map(service -> Response.ok(ServiceRegistrationResponse.fromModel(service))
                        .build())
                .orElse(Response.status(Response.Status.NOT_FOUND)
                        .entity(Map.of("error", "Service not found: " + serviceId))
                        .build());
    }
}
