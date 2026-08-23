package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;

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
import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.ProxyPlan;
import aussie.core.port.in.PassThroughUseCase;

@ExtendWith(MockitoExtension.class)
class PassThroughResourceTest {

    @Mock
    private PassThroughUseCase passThroughUseCase;

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

    private PassThroughResource resource;

    @BeforeEach
    void setUp() {
        resource = new PassThroughResource(passThroughUseCase, routingContext, clientContextResolver, proxyExchange);
        lenient().when(routingContext.request()).thenReturn(httpRequest);
        lenient().when(requestContext.getMethod()).thenReturn("POST");
        lenient().when(requestContext.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        lenient().when(requestContext.getUriInfo()).thenReturn(uriInfo);
        lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost/users/api/items"));
        lenient()
                .when(clientContextResolver.getOrCompute(routingContext))
                .thenReturn(new ClientContext("192.0.2.1", false, null));
        lenient()
                .when(proxyExchange.forward(any(), any(), eq(false)))
                .thenReturn(Multi.createFrom().empty());
    }

    @Test
    void preparesMetadataWithoutBufferingTheBody() {
        when(passThroughUseCase.prepare(eq("users"), any()))
                .thenReturn(Uni.createFrom()
                        .item(new ProxyPlan.Rejected(new GatewayResult.ServiceNotFound("users"), null)));

        resource.proxyPost("users", "api/items", requestContext);

        final var request = ArgumentCaptor.forClass(GatewayRequest.class);
        verify(passThroughUseCase).prepare(eq("users"), request.capture());
        assertEquals("/api/items", request.getValue().path());
        assertEquals(0, request.getValue().body().length);
        verify(proxyExchange).forward(any(), any(), eq(false));
    }

    @Test
    void normalizesAnEmptyPath() {
        when(passThroughUseCase.prepare(eq("users"), any()))
                .thenReturn(Uni.createFrom()
                        .item(new ProxyPlan.Rejected(new GatewayResult.ServiceNotFound("users"), null)));

        resource.proxyGet("users", "", requestContext);

        final var request = ArgumentCaptor.forClass(GatewayRequest.class);
        verify(passThroughUseCase).prepare(eq("users"), request.capture());
        assertEquals("/", request.getValue().path());
    }
}
