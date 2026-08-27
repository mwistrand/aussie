package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.in.vertx.StreamingProxyExchange;
import aussie.common.context.ClientContext;
import aussie.common.context.RouteContextAttributes;
import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.ProxyPlan;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.in.GatewayUseCase;

@ExtendWith(MockitoExtension.class)
class GatewayResourceTest {

    @Mock
    private GatewayUseCase gatewayUseCase;

    @Mock
    private RoutingContext routingContext;

    @Mock
    private ContainerRequestContext requestContext;

    @Mock
    private UriInfo uriInfo;

    @Mock
    private HttpServerRequest httpRequest;

    @Mock
    private ClientContextResolver clientContextResolver;

    @Mock
    private StreamingProxyExchange proxyExchange;

    private GatewayResource resource;

    @BeforeEach
    void setUp() {
        resource = new GatewayResource(gatewayUseCase, routingContext, clientContextResolver, proxyExchange);
        lenient().when(routingContext.request()).thenReturn(httpRequest);
        lenient().when(requestContext.getMethod()).thenReturn("POST");
        lenient().when(requestContext.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
        lenient()
                .when(requestContext.getProperty(RouteContextAttributes.LOOKUP))
                .thenReturn(Optional.empty());
        lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost/gateway/api/users?q=1"));
        lenient()
                .when(clientContextResolver.getOrCompute(routingContext))
                .thenReturn(new ClientContext("192.0.2.1", false, null));
        lenient()
                .when(proxyExchange.forward(any(), any(), eq(false)))
                .thenReturn(Multi.createFrom().empty());
    }

    @Test
    void preparesMetadataWithoutBufferingTheBody() {
        final var route = new RouteMatch(
                ServiceRegistration.builder("backend").baseUrl("http://backend").build(),
                new EndpointConfig("/api/users", Set.of("POST"), EndpointVisibility.PUBLIC, Optional.empty(), false),
                "/api/users",
                Map.of());
        when(requestContext.getProperty(RouteContextAttributes.LOOKUP)).thenReturn(Optional.of(route));
        when(gatewayUseCase.prepare(any()))
                .thenReturn(Uni.createFrom().item(new ProxyPlan.Rejected(new GatewayResult.RouteNotFound("/"), null)));

        resource.proxyPost("api/users", requestContext);

        final var request = ArgumentCaptor.forClass(GatewayRequest.class);
        verify(gatewayUseCase).prepare(request.capture());
        assertEquals("/api/users", request.getValue().path());
        assertEquals(0, request.getValue().body().length);
        assertEquals(Optional.of(route), request.getValue().resolvedRoute());
        assertTrue(request.getValue().hasRouteSnapshot());
        verify(proxyExchange).forward(any(), any(), eq(false));
    }

    @Test
    void headSuppressesTheUpstreamBody() {
        when(gatewayUseCase.prepare(any()))
                .thenReturn(Uni.createFrom()
                        .item(new ProxyPlan.Rejected(new GatewayResult.RouteNotFound("/status"), null)));
        when(proxyExchange.forward(any(), any(), eq(true)))
                .thenReturn(Multi.createFrom().empty());

        resource.proxyHead("status", requestContext);

        verify(proxyExchange).forward(any(), any(), eq(true));
    }
}
