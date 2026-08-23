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
import aussie.core.port.in.PassThroughUseCase;

/**
 * REST resource for pass-through proxy requests.
 *
 * <p>Exposes endpoints under {@code /{serviceId}/{path}} that forward requests
 * directly to the specified backend service. Supports all standard HTTP methods
 * (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS).
 *
 * <p>This resource has the lowest path priority and handles any requests not
 * matched by more specific resources (admin, gateway, etc.).
 */
@Path("/{serviceId}")
@ApplicationScoped
public class PassThroughResource {

    private final PassThroughUseCase passThroughUseCase;
    private final RoutingContext routingContext;
    private final ClientContextResolver clientContextResolver;
    private final StreamingProxyExchange proxyExchange;

    @Inject
    public PassThroughResource(
            PassThroughUseCase passThroughUseCase,
            RoutingContext routingContext,
            ClientContextResolver clientContextResolver,
            StreamingProxyExchange proxyExchange) {
        this.passThroughUseCase = passThroughUseCase;
        this.routingContext = routingContext;
        this.clientContextResolver = clientContextResolver;
        this.proxyExchange = proxyExchange;
    }

    @GET
    @Path("{path:.*}")
    public Multi<io.vertx.core.buffer.Buffer> proxyGet(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext);
    }

    @POST
    @Path("{path:.*}")
    public Multi<io.vertx.core.buffer.Buffer> proxyPost(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext);
    }

    @PUT
    @Path("{path:.*}")
    public Multi<io.vertx.core.buffer.Buffer> proxyPut(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext);
    }

    @DELETE
    @Path("{path:.*}")
    public Multi<io.vertx.core.buffer.Buffer> proxyDelete(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext);
    }

    @PATCH
    @Path("{path:.*}")
    public Multi<io.vertx.core.buffer.Buffer> proxyPatch(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext);
    }

    @HEAD
    @Path("{path:.*}")
    public Multi<io.vertx.core.buffer.Buffer> proxyHead(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext, true);
    }

    @OPTIONS
    @Path("{path:.*}")
    public Multi<io.vertx.core.buffer.Buffer> proxyOptions(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext);
    }

    private Multi<io.vertx.core.buffer.Buffer> proxyRequest(
            String serviceId, String path, ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext, false);
    }

    private Multi<io.vertx.core.buffer.Buffer> proxyRequest(
            String serviceId, String path, ContainerRequestContext requestContext, boolean suppressResponseBody) {
        var targetPath = path.isEmpty() ? "/" : "/" + path;
        final var request = HttpServerRequest.newInstance(routingContext.request());
        final var prepared = passThroughUseCase.prepare(serviceId, toGatewayRequest(targetPath, requestContext));
        return proxyExchange.forward(prepared, request, suppressResponseBody);
    }

    private GatewayRequest toGatewayRequest(String path, ContainerRequestContext requestContext) {
        // MultivaluedMap<String, String> IS-A Map<String, List<String>>. The map is read-only
        // for the rest of this request, so passing it directly avoids a per-request HashMap +
        // per-value List.copyOf round-trip.
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
