package aussie.core.service.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.PreparedProxyRequest;
import aussie.core.model.gateway.ProxyPlan;
import aussie.core.model.gateway.RouteAuthResult;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.routing.EndpointMatcher;
import aussie.core.service.routing.ServiceRegistry;
import aussie.core.service.routing.VisibilityResolver;

class PassThroughServiceTest {

    private ServiceRegistry registry;
    private ProxyRequestPreparer preparer;
    private RouteAuthenticationService authentication;
    private VisibilityResolver visibility;
    private EndpointMatcher endpointMatcher;
    private PassThroughService service;

    @BeforeEach
    void setUp() {
        registry = mock(ServiceRegistry.class);
        preparer = mock(ProxyRequestPreparer.class);
        authentication = mock(RouteAuthenticationService.class);
        visibility = mock(VisibilityResolver.class);
        endpointMatcher = mock(EndpointMatcher.class);
        service = new PassThroughService(registry, preparer, visibility, endpointMatcher, authentication);
    }

    @Test
    void rejectsReservedAndMissingServices() {
        final var reserved = service.prepare("ADMIN", request()).await().atMost(Duration.ofSeconds(1));
        when(registry.getService("missing")).thenReturn(Uni.createFrom().item(Optional.empty()));
        final var missing = service.prepare("missing", request()).await().atMost(Duration.ofSeconds(1));

        assertInstanceOf(GatewayResult.ReservedPath.class, ((ProxyPlan.Rejected) reserved).result());
        assertInstanceOf(GatewayResult.ServiceNotFound.class, ((ProxyPlan.Rejected) missing).result());
    }

    @Test
    void preparesTheResolvedServiceWithoutExecutingAnotherProxyPath() {
        final var registration =
                ServiceRegistration.builder("backend").baseUrl("http://backend").build();
        final var prepared = new PreparedProxyRequest("GET", URI.create("http://backend/items"), Map.of(), null);
        when(registry.getService("backend")).thenReturn(Uni.createFrom().item(Optional.of(registration)));
        when(endpointMatcher.match(any(), any(), any())).thenReturn(Optional.empty());
        when(visibility.resolve(any(), any(), any())).thenReturn(EndpointVisibility.PUBLIC);
        when(authentication.authenticate(any(), any()))
                .thenReturn(Uni.createFrom().item(new RouteAuthResult.NotRequired()));
        when(preparer.prepare(any(), any(), any())).thenReturn(prepared);

        final var plan = assertInstanceOf(
                ProxyPlan.Ready.class,
                service.prepare("backend", request()).await().atMost(Duration.ofSeconds(1)));

        assertEquals(prepared, plan.request());
    }

    @Test
    void preservesAuthenticationRejectionsInTheProxyPlan() {
        final var registration =
                ServiceRegistration.builder("backend").baseUrl("http://backend").build();
        when(registry.getService("backend")).thenReturn(Uni.createFrom().item(Optional.of(registration)));
        when(endpointMatcher.match(any(), any(), any())).thenReturn(Optional.empty());
        when(visibility.resolve(any(), any(), any())).thenReturn(EndpointVisibility.PUBLIC);
        when(authentication.authenticate(any(), any()))
                .thenReturn(Uni.createFrom().item(new RouteAuthResult.Unauthorized("expired")))
                .thenReturn(Uni.createFrom().item(new RouteAuthResult.Forbidden("denied")))
                .thenReturn(Uni.createFrom().item(new RouteAuthResult.BadRequest("conflicting credentials")));

        final var unauthorized = (ProxyPlan.Rejected)
                service.prepare("backend", request()).await().atMost(Duration.ofSeconds(1));
        final var forbidden = (ProxyPlan.Rejected)
                service.prepare("backend", request()).await().atMost(Duration.ofSeconds(1));
        final var badRequest = (ProxyPlan.Rejected)
                service.prepare("backend", request()).await().atMost(Duration.ofSeconds(1));

        assertInstanceOf(GatewayResult.Unauthorized.class, unauthorized.result());
        assertInstanceOf(GatewayResult.Forbidden.class, forbidden.result());
        assertInstanceOf(GatewayResult.BadRequest.class, badRequest.result());
        assertEquals("backend", unauthorized.serviceId());
        assertEquals("backend", forbidden.serviceId());
        assertEquals("backend", badRequest.serviceId());
    }

    private GatewayRequest request() {
        return new GatewayRequest("GET", "/items", Map.of(), URI.create("http://gateway/items"), null, "127.0.0.1");
    }
}
