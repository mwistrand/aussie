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
    private final ProxyResourceSupport proxySupport;

    @Inject
    public PassThroughResource(
            PassThroughUseCase passThroughUseCase,
            RoutingContext routingContext,
            ClientContextResolver clientContextResolver,
            StreamingProxyExchange proxyExchange) {
        this.passThroughUseCase = passThroughUseCase;
        this.proxySupport = new ProxyResourceSupport(routingContext, clientContextResolver, proxyExchange);
    }

    @GET
    @Path("{path:.*}")
    public Multi<Buffer> proxyGet(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext);
    }

    @POST
    @Path("{path:.*}")
    public Multi<Buffer> proxyPost(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext);
    }

    @PUT
    @Path("{path:.*}")
    public Multi<Buffer> proxyPut(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext);
    }

    @DELETE
    @Path("{path:.*}")
    public Multi<Buffer> proxyDelete(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext);
    }

    @PATCH
    @Path("{path:.*}")
    public Multi<Buffer> proxyPatch(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext);
    }

    @HEAD
    @Path("{path:.*}")
    public Multi<Buffer> proxyHead(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext, true);
    }

    @OPTIONS
    @Path("{path:.*}")
    public Multi<Buffer> proxyOptions(
            @PathParam("serviceId") String serviceId,
            @PathParam("path") String path,
            @Context ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext);
    }

    private Multi<Buffer> proxyRequest(String serviceId, String path, ContainerRequestContext requestContext) {
        return proxyRequest(serviceId, path, requestContext, false);
    }

    private Multi<Buffer> proxyRequest(
            String serviceId, String path, ContainerRequestContext requestContext, boolean suppressResponseBody) {
        final var targetPath = path.isEmpty() ? "/" : "/" + path;
        final var gatewayRequest = proxySupport.request(targetPath, requestContext);
        return proxySupport.forward(passThroughUseCase.prepare(serviceId, gatewayRequest), suppressResponseBody);
    }
}
