package aussie.adapter.in.rest;

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

import io.smallrye.mutiny.Multi;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.in.vertx.StreamingProxyExchange;
import aussie.core.port.in.GatewayUseCase;

/**
 * REST resource for gateway-mode proxy requests.
 *
 * <p>Exposes endpoints under {@code /gateway/{path}} that forward requests
 * to backend services based on configured route matching. Supports all
 * standard HTTP methods (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS).
 */
@Path("/gateway")
@ApplicationScoped
public class GatewayResource {

    private final GatewayUseCase gatewayUseCase;
    private final ProxyResourceSupport proxySupport;

    @Inject
    public GatewayResource(
            GatewayUseCase gatewayUseCase,
            RoutingContext routingContext,
            ClientContextResolver clientContextResolver,
            StreamingProxyExchange proxyExchange) {
        this.gatewayUseCase = gatewayUseCase;
        this.proxySupport = new ProxyResourceSupport(routingContext, clientContextResolver, proxyExchange);
    }

    @GET
    @Path("{path:.*}")
    public Multi<Buffer> proxyGet(@PathParam("path") String path, @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext);
    }

    @POST
    @Path("{path:.*}")
    public Multi<Buffer> proxyPost(@PathParam("path") String path, @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext);
    }

    @PUT
    @Path("{path:.*}")
    public Multi<Buffer> proxyPut(@PathParam("path") String path, @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext);
    }

    @DELETE
    @Path("{path:.*}")
    public Multi<Buffer> proxyDelete(@PathParam("path") String path, @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext);
    }

    @PATCH
    @Path("{path:.*}")
    public Multi<Buffer> proxyPatch(@PathParam("path") String path, @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext);
    }

    @HEAD
    @Path("{path:.*}")
    public Multi<Buffer> proxyHead(@PathParam("path") String path, @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext, true);
    }

    @OPTIONS
    @Path("{path:.*}")
    public Multi<Buffer> proxyOptions(@PathParam("path") String path, @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext);
    }

    private Multi<Buffer> proxyRequest(String path, ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext, false);
    }

    private Multi<Buffer> proxyRequest(
            String path, ContainerRequestContext requestContext, boolean suppressResponseBody) {
        final var gatewayRequest = proxySupport.request("/" + path, requestContext);
        return proxySupport.forward(gatewayUseCase.prepare(gatewayRequest), suppressResponseBody);
    }
}
