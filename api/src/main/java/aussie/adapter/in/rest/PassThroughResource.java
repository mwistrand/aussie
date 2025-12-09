package aussie.adapter.in.rest;

import java.util.HashMap;
import java.util.List;

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

import aussie.core.model.GatewayRequest;
import aussie.core.model.GatewayResult;
import aussie.core.port.in.PassThroughUseCase;

@Path("/{serviceId}")
@ApplicationScoped
public class PassThroughResource {

    private final PassThroughUseCase passThroughUseCase;

    @Inject
    public PassThroughResource(PassThroughUseCase passThroughUseCase) {
        this.passThroughUseCase = passThroughUseCase;
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
        var targetPath = path.isEmpty() ? "/" : "/" + path;
        var gatewayRequest = toGatewayRequest(targetPath, requestContext, body);
        return passThroughUseCase.forward(serviceId, gatewayRequest).map(this::toResponse);
    }

    private GatewayRequest toGatewayRequest(String path, ContainerRequestContext requestContext, byte[] body) {
        var headers = new HashMap<String, List<String>>();
        for (var entry : requestContext.getHeaders().entrySet()) {
            headers.put(entry.getKey(), List.copyOf(entry.getValue()));
        }

        return new GatewayRequest(
                requestContext.getMethod(),
                path,
                headers,
                requestContext.getUriInfo().getRequestUri(),
                body);
    }

    private Response toResponse(GatewayResult result) {
        return switch (result) {
            case GatewayResult.Success success -> {
                var responseBuilder = Response.status(success.statusCode());
                for (var entry : success.headers().entrySet()) {
                    for (var value : entry.getValue()) {
                        responseBuilder.header(entry.getKey(), value);
                    }
                }
                if (success.body().length > 0) {
                    responseBuilder.entity(success.body());
                }
                yield responseBuilder.build();
            }
            case GatewayResult.ServiceNotFound notFound -> Response.status(Response.Status.NOT_FOUND)
                    .entity("Service not found")
                    .build();
            case GatewayResult.ReservedPath reserved -> Response.status(Response.Status.NOT_FOUND)
                    .entity("Not found")
                    .build();
            case GatewayResult.RouteNotFound notFound -> Response.status(Response.Status.NOT_FOUND)
                    .entity("Not found")
                    .build();
            case GatewayResult.Error error -> Response.status(Response.Status.BAD_GATEWAY)
                    .entity("Error forwarding request")
                    .build();
            case GatewayResult.Unauthorized unauthorized -> Response.status(Response.Status.UNAUTHORIZED)
                    .header("WWW-Authenticate", "Bearer realm=\"aussie\"")
                    .entity("Unauthorized")
                    .build();
            case GatewayResult.Forbidden forbidden -> Response.status(Response.Status.FORBIDDEN)
                    .entity("Forbidden")
                    .build();
            case GatewayResult.BadRequest badRequest -> Response.status(Response.Status.BAD_REQUEST)
                    .entity("Bad request")
                    .build();
        };
    }
}
