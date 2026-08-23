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
import io.vertx.ext.web.RoutingContext;
import io.vertx.mutiny.core.http.HttpServerRequest;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.in.vertx.StreamingProxyExchange;
import aussie.core.model.gateway.GatewayRequest;
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
    private final RoutingContext routingContext;
    private final ClientContextResolver clientContextResolver;
    private final StreamingProxyExchange proxyExchange;

    @Inject
    public GatewayResource(
            GatewayUseCase gatewayUseCase,
            RoutingContext routingContext,
            ClientContextResolver clientContextResolver,
            StreamingProxyExchange proxyExchange) {
        this.gatewayUseCase = gatewayUseCase;
        this.routingContext = routingContext;
        this.clientContextResolver = clientContextResolver;
        this.proxyExchange = proxyExchange;
    }

    @GET
    @Path("{path:.*}")
    public Multi<io.vertx.core.buffer.Buffer> proxyGet(
            @PathParam("path") String path, @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext);
    }

    @POST
    @Path("{path:.*}")
    public Multi<io.vertx.core.buffer.Buffer> proxyPost(
            @PathParam("path") String path, @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext);
    }

    @PUT
    @Path("{path:.*}")
    public Multi<io.vertx.core.buffer.Buffer> proxyPut(
            @PathParam("path") String path, @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext);
    }

    @DELETE
    @Path("{path:.*}")
    public Multi<io.vertx.core.buffer.Buffer> proxyDelete(
            @PathParam("path") String path, @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext);
    }

    @PATCH
    @Path("{path:.*}")
    public Multi<io.vertx.core.buffer.Buffer> proxyPatch(
            @PathParam("path") String path, @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext);
    }

    @HEAD
    @Path("{path:.*}")
    public Multi<io.vertx.core.buffer.Buffer> proxyHead(
            @PathParam("path") String path, @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext, true);
    }

    @OPTIONS
    @Path("{path:.*}")
    public Multi<io.vertx.core.buffer.Buffer> proxyOptions(
            @PathParam("path") String path, @Context ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext);
    }

    private Multi<io.vertx.core.buffer.Buffer> proxyRequest(String path, ContainerRequestContext requestContext) {
        return proxyRequest(path, requestContext, false);
    }

    private Multi<io.vertx.core.buffer.Buffer> proxyRequest(
            String path, ContainerRequestContext requestContext, boolean suppressResponseBody) {
        final var request = HttpServerRequest.newInstance(routingContext.request());
        final var prepared = gatewayUseCase.prepare(toGatewayRequest("/" + path, requestContext));
        return proxyExchange.forward(prepared, request, suppressResponseBody);
    }

    private GatewayRequest toGatewayRequest(String path, ContainerRequestContext requestContext) {
        // MultivaluedMap<String, String> IS-A Map<String, List<String>>; pass it through
        // instead of materialising a defensive copy that the downstream pipeline never mutates.
        final var clientContext = clientContextResolver.getOrCompute(routingContext);
        return new GatewayRequest(
                requestContext.getMethod(),
                path,
                requestContext.getHeaders(),
                requestContext.getUriInfo().getRequestUri(),
                null,
                clientContext.resolvedIp(),
                clientContext.externalScheme(),
                clientContext.externalHost(),
                clientContext.externalPort());
    }
}
