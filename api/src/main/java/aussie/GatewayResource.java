package aussie;

import aussie.proxy.ProxyHttpClient;
import aussie.proxy.ProxyResponse;
import aussie.routing.ServiceRegistry;
import io.smallrye.mutiny.Uni;
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

@Path("/gateway")
@ApplicationScoped
public class GatewayResource {

    private final ServiceRegistry serviceRegistry;
    private final ProxyHttpClient proxyClient;

    @Inject
    public GatewayResource(ServiceRegistry serviceRegistry, ProxyHttpClient proxyClient) {
        this.serviceRegistry = serviceRegistry;
        this.proxyClient = proxyClient;
    }

    @GET
    @Path("{path:.*}")
    public Uni<Response> proxyGet(
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext, null);
    }

    @POST
    @Path("{path:.*}")
    public Uni<Response> proxyPost(
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext,
            byte[] body) {
        return proxyRequest(path, requestContext, body);
    }

    @PUT
    @Path("{path:.*}")
    public Uni<Response> proxyPut(
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext,
            byte[] body) {
        return proxyRequest(path, requestContext, body);
    }

    @DELETE
    @Path("{path:.*}")
    public Uni<Response> proxyDelete(
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext, null);
    }

    @PATCH
    @Path("{path:.*}")
    public Uni<Response> proxyPatch(
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext,
            byte[] body) {
        return proxyRequest(path, requestContext, body);
    }

    @HEAD
    @Path("{path:.*}")
    public Uni<Response> proxyHead(
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext, null);
    }

    @OPTIONS
    @Path("{path:.*}")
    public Uni<Response> proxyOptions(
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext, null);
    }

    private Uni<Response> proxyRequest(String path, ContainerRequestContext requestContext, byte[] body) {
        var method = requestContext.getMethod();
        var routeMatch = serviceRegistry.findRoute("/" + path, method);

        if (routeMatch.isEmpty()) {
            return Uni.createFrom().item(
                Response.status(Response.Status.NOT_FOUND)
                    .entity("No route found for path: /" + path)
                    .build()
            );
        }

        return proxyClient.forward(requestContext, routeMatch.get(), body)
            .map(this::buildResponse)
            .onFailure().recoverWithItem(this::buildErrorResponse);
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
