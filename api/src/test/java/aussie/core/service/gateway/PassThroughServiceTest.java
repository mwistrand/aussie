package aussie.core.service.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.PreparedProxyRequest;
import aussie.core.model.gateway.ProxyPlan;
import aussie.core.model.gateway.RouteAuthResult;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.routing.ServiceOnlyMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.routing.ServiceRegistry;
import aussie.core.service.routing.VisibilityResolver;

class PassThroughServiceTest {

    private ServiceRegistry registry;
    private ProxyRequestPreparer preparer;
    private RouteAuthenticationService authentication;
    private VisibilityResolver visibility;
    private PassThroughService service;

    @BeforeEach
    void setUp() {
        registry = mock(ServiceRegistry.class);
        preparer = mock(ProxyRequestPreparer.class);
        authentication = mock(RouteAuthenticationService.class);
        visibility = mock(VisibilityResolver.class);
        service = new PassThroughService(registry, visibility, authentication, new ProxyPlanBuilder(preparer));
    }

    @Test
    void rejectsReservedAndMissingServices() {
        final var reserved = service.prepare("ADMIN", request()).await().atMost(Duration.ofSeconds(1));
        when(registry.findServiceRouteAsync("missing", "/items", "GET"))
                .thenReturn(Uni.createFrom().item(Optional.empty()));
        final var missing = service.prepare("missing", request()).await().atMost(Duration.ofSeconds(1));

        assertInstanceOf(GatewayResult.ReservedPath.class, ((ProxyPlan.Rejected) reserved).result());
        assertInstanceOf(GatewayResult.ServiceNotFound.class, ((ProxyPlan.Rejected) missing).result());
    }

    @Test
    void preparesTheResolvedServiceWithoutExecutingAnotherProxyPath() {
        final var registration =
                ServiceRegistration.builder("backend").baseUrl("http://backend").build();
        final var prepared = new PreparedProxyRequest("GET", URI.create("http://backend/items"), Map.of(), null);
        when(registry.findServiceRouteAsync("backend", "/items", "GET"))
                .thenReturn(Uni.createFrom().item(Optional.of(new ServiceOnlyMatch(registration))));
        when(visibility.resolve(any(), any(), any())).thenReturn(EndpointVisibility.PUBLIC);
        when(authentication.authenticate(any(), any()))
                .thenReturn(Uni.createFrom().item(new RouteAuthResult.NotRequired()));
        when(preparer.prepare(any(), any())).thenReturn(prepared);

        final var plan = assertInstanceOf(
                ProxyPlan.Ready.class,
                service.prepare("backend", request()).await().atMost(Duration.ofSeconds(1)));

        assertEquals(prepared, plan.request());
    }

    @Test
    void usesTheSnapshotRouteAndPreservesItsRewrite() {
        final var registration =
                ServiceRegistration.builder("backend").baseUrl("http://backend").build();
        final var endpoint =
                new EndpointConfig("/items", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.of("/v2/items"), false);
        final var route = new RouteMatch(registration, endpoint, "/v2/items", Map.of());
        final var prepared = new PreparedProxyRequest("GET", URI.create("http://backend/v2/items"), Map.of(), null);
        when(registry.findServiceRouteAsync("backend", "/items", "GET"))
                .thenReturn(Uni.createFrom().item(Optional.of(route)));
        when(authentication.authenticate(any(), any()))
                .thenReturn(Uni.createFrom().item(new RouteAuthResult.NotRequired()));
        when(preparer.prepare(any(), any())).thenReturn(prepared);

        final var plan = assertInstanceOf(
                ProxyPlan.Ready.class,
                service.prepare("backend", request()).await().atMost(Duration.ofSeconds(1)));

        assertEquals(prepared, plan.request());
        verify(authentication).authenticate(any(), same(route));
        verify(preparer).prepare(any(), same(route));
    }

    @Test
    void usesTheResolvedRouteWithoutQueryingTheRegistryAgain() {
        final var registration =
                ServiceRegistration.builder("backend").baseUrl("http://backend").build();
        final var endpoint =
                new EndpointConfig("/items", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), false);
        final var route = new RouteMatch(registration, endpoint, "/items", Map.of());
        final var prepared = new PreparedProxyRequest("GET", URI.create("http://backend/items"), Map.of(), null);
        when(authentication.authenticate(any(), same(route)))
                .thenReturn(Uni.createFrom().item(new RouteAuthResult.NotRequired()));
        when(preparer.prepare(any(), same(route))).thenReturn(prepared);

        final var plan = assertInstanceOf(
                ProxyPlan.Ready.class,
                service.prepare("backend", request(Optional.of(route))).await().atMost(Duration.ofSeconds(1)));

        assertEquals(prepared, plan.request());
        verifyNoInteractions(registry);
    }

    @Test
    void usesAnEmptyRouteSnapshotWithoutQueryingTheRegistryAgain() {
        final var plan = assertInstanceOf(
                ProxyPlan.Rejected.class,
                service.prepare("backend", request(Optional.empty())).await().atMost(Duration.ofSeconds(1)));

        assertInstanceOf(GatewayResult.ServiceNotFound.class, plan.result());
        verifyNoInteractions(registry, authentication, preparer);
    }

    @Test
    void ignoresAResolvedRouteForAnotherService() {
        final var wrongRoute = new RouteMatch(
                ServiceRegistration.builder("other").baseUrl("http://other").build(),
                new EndpointConfig("/items", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), false),
                "/items",
                Map.of());
        final var route = new RouteMatch(
                ServiceRegistration.builder("backend").baseUrl("http://backend").build(),
                new EndpointConfig("/items", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), false),
                "/items",
                Map.of());
        final var prepared = new PreparedProxyRequest("GET", URI.create("http://backend/items"), Map.of(), null);
        when(registry.findServiceRouteAsync("backend", "/items", "GET"))
                .thenReturn(Uni.createFrom().item(Optional.of(route)));
        when(authentication.authenticate(any(), same(route)))
                .thenReturn(Uni.createFrom().item(new RouteAuthResult.NotRequired()));
        when(preparer.prepare(any(), same(route))).thenReturn(prepared);

        final var plan = assertInstanceOf(
                ProxyPlan.Ready.class,
                service.prepare("backend", request(Optional.of(wrongRoute)))
                        .await()
                        .atMost(Duration.ofSeconds(1)));

        assertEquals(prepared, plan.request());
        verify(registry).findServiceRouteAsync("backend", "/items", "GET");
    }

    @Test
    void preservesAuthenticationRejectionsInTheProxyPlan() {
        final var registration =
                ServiceRegistration.builder("backend").baseUrl("http://backend").build();
        when(registry.findServiceRouteAsync("backend", "/items", "GET"))
                .thenReturn(Uni.createFrom().item(Optional.of(new ServiceOnlyMatch(registration))));
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

    private GatewayRequest request(Optional<RouteMatch> route) {
        return new GatewayRequest(
                "GET",
                "/items",
                Map.of(),
                URI.create("http://gateway/items"),
                null,
                "127.0.0.1",
                null,
                null,
                null,
                route.map(value -> (RouteLookupResult) value),
                true);
    }
}
