package aussie.adapter.in.rest;

import jakarta.ws.rs.container.ContainerRequestContext;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import io.vertx.mutiny.core.http.HttpServerRequest;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.in.vertx.StreamingProxyExchange;
import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.ProxyPlan;

/** Shared HTTP proxy transport for gateway and pass-through resources. */
final class ProxyResourceSupport {

    private final RoutingContext routingContext;
    private final ClientContextResolver clientContextResolver;
    private final StreamingProxyExchange proxyExchange;

    ProxyResourceSupport(
            RoutingContext routingContext,
            ClientContextResolver clientContextResolver,
            StreamingProxyExchange proxyExchange) {
        this.routingContext = routingContext;
        this.clientContextResolver = clientContextResolver;
        this.proxyExchange = proxyExchange;
    }

    Multi<Buffer> forward(Uni<ProxyPlan> prepared, boolean suppressResponseBody) {
        return proxyExchange.forward(
                prepared, HttpServerRequest.newInstance(routingContext.request()), suppressResponseBody);
    }

    GatewayRequest request(String path, ContainerRequestContext requestContext) {
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
