package aussie.adapter.in.rest;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;

import aussie.core.model.EndpointConfig;
import aussie.core.model.EndpointVisibility;
import aussie.core.model.ProxyResponse;
import aussie.core.model.RouteMatch;
import aussie.core.model.ServiceRegistration;
import aussie.core.port.out.ProxyClient;
import aussie.core.service.ServiceRegistry;

@Path("/{serviceId}")
@ApplicationScoped
public class PassThroughResource {

    private static final Set<String> RESERVED_PATHS = Set.of("admin", "gateway", "q");

    private final ServiceRegistry serviceRegistry;
    private final ProxyClient proxyClient;

    @Inject
    public PassThroughResource(ServiceRegistry serviceRegistry, ProxyClient proxyClient) {
        this.serviceRegistry = serviceRegistry;
        this.proxyClient = proxyClient;
    }

    @GET
    @Path("{path:.*}")
    public Uni<Response> proxyGet(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext, null);
    }

    @POST
    @Path("{path:.*}")
    public Uni<Response> proxyPost(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext,
            byte[] body) {
        return proxyRequest(serviceId, path, requestContext, body);
    }

    @PUT
    @Path("{path:.*}")
    public Uni<Response> proxyPut(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext,
            byte[] body) {
        return proxyRequest(serviceId, path, requestContext, body);
    }

    @DELETE
    @Path("{path:.*}")
    public Uni<Response> proxyDelete(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext, null);
    }

    @PATCH
    @Path("{path:.*}")
    public Uni<Response> proxyPatch(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext,
            byte[] body) {
        return proxyRequest(serviceId, path, requestContext, body);
    }

    @HEAD
    @Path("{path:.*}")
    public Uni<Response> proxyHead(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext, null);
    }

    @OPTIONS
    @Path("{path:.*}")
    public Uni<Response> proxyOptions(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext, null);
    }

    private Uni<Response> proxyRequest(
            String serviceId, String path, ContainerRequestContext requestContext, byte[] body) {
        if (RESERVED_PATHS.contains(serviceId.toLowerCase())) {
            return Uni.createFrom()
                    .item(Response.status(Response.Status.NOT_FOUND)
                            .entity("Not found")
                            .build());
        }

        var serviceOpt = serviceRegistry.getService(serviceId);
        if (serviceOpt.isEmpty()) {
            return Uni.createFrom()
                    .item(Response.status(Response.Status.NOT_FOUND)
                            .entity("Service not found: " + serviceId)
                            .build());
        }

        var service = serviceOpt.get();
        var targetPath = path.isEmpty() ? "/" : "/" + path;
        var routeMatch = createPassThroughRouteMatch(service, targetPath);

        return proxyClient
                .forward(requestContext, routeMatch, body)
                .map(this::buildResponse)
                .onFailure()
                .recoverWithItem(this::buildErrorResponse);
    }

    private RouteMatch createPassThroughRouteMatch(ServiceRegistration service, String targetPath) {
        var catchAllEndpoint = new EndpointConfig("/**", Set.of("*"), EndpointVisibility.PUBLIC, Optional.empty());
        return new RouteMatch(service, catchAllEndpoint, targetPath, Map.of());
    }

    private Response buildResponse(ProxyResponse proxyResponse) {
        var responseBuilder = Response.status(proxyResponse.statusCode());

        for (var entry : proxyResponse.headers().entrySet()) {
            for (var value : entry.getValue()) {
                responseBuilder.header(entry.getKey(), value);
            }
        }

        if (proxyResponse.body().length > 0) {
            responseBuilder.entity(proxyResponse.body());
        }

        return responseBuilder.build();
    }

    private Response buildErrorResponse(Throwable error) {
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity("Error forwarding request: " + error.getMessage())
                .build();
    }
}
