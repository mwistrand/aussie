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

import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
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
            case GatewayResult.ServiceNotFound s -> throw GatewayProblem.serviceNotFound(s.serviceId());
            case GatewayResult.ReservedPath rp -> throw GatewayProblem.notFound(
                    "Path '%s' is reserved".formatted(rp.path()));
            case GatewayResult.RouteNotFound r -> throw GatewayProblem.routeNotFound(r.path());
            case GatewayResult.Error e -> throw GatewayProblem.badGateway(e.message());
            case GatewayResult.Unauthorized u -> throw GatewayProblem.unauthorized(u.reason());
            case GatewayResult.Forbidden f -> throw GatewayProblem.forbidden(f.reason());
            case GatewayResult.BadRequest b -> throw GatewayProblem.badRequest(b.reason());
        };
    }
}
