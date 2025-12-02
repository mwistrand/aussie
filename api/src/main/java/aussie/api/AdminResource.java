package aussie.api;

import java.util.List;

import aussie.api.dto.ServiceRegistrationRequest;
import aussie.api.dto.ServiceRegistrationResponse;
import aussie.routing.ServiceRegistry;
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

@Path("/admin/services")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

    private final ServiceRegistry registry;

    @Inject
    public AdminResource(ServiceRegistry registry) {
        this.registry = registry;
    }

    @POST
    public Response registerService(ServiceRegistrationRequest request) {
        if (request == null || request.serviceId() == null || request.baseUrl() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\": \"serviceId and baseUrl are required\"}")
                .build();
        }

        try {
            var service = request.toModel();
            registry.register(service);
            return Response.status(Response.Status.CREATED)
                .entity(ServiceRegistrationResponse.fromModel(service))
                .build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\": \"" + e.getMessage() + "\"}")
                .build();
        }
    }

    @DELETE
    @Path("/{serviceId}")
    public Response unregisterService(@PathParam("serviceId") String serviceId) {
        var existing = registry.getService(serviceId);
        if (existing.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\": \"Service not found: " + serviceId + "\"}")
                .build();
        }

        registry.unregister(serviceId);
        return Response.noContent().build();
    }

    @GET
    public List<ServiceRegistrationResponse> listServices() {
        return registry.getAllServices().stream()
            .map(ServiceRegistrationResponse::fromModel)
            .toList();
    }

    @GET
    @Path("/{serviceId}")
    public Response getService(@PathParam("serviceId") String serviceId) {
        return registry.getService(serviceId)
            .map(service -> Response.ok(ServiceRegistrationResponse.fromModel(service)).build())
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\": \"Service not found: " + serviceId + "\"}")
                .build());
    }
}
