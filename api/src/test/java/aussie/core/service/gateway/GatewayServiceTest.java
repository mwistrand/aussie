package aussie.core.service.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.AussieToken;
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

class GatewayServiceTest {

    private ServiceRegistry registry;
    private ProxyRequestPreparer preparer;
    private RouteAuthenticationService authentication;
    private GatewayService service;

    @BeforeEach
    void setUp() {
        registry = mock(ServiceRegistry.class);
        preparer = mock(ProxyRequestPreparer.class);
        authentication = mock(RouteAuthenticationService.class);
        service = new GatewayService(registry, authentication, new ProxyPlanBuilder(preparer));
    }

    @Test
    void rejectsMissingAndServiceOnlyRoutes() {
        when(registry.findRouteAsync(any(), any()))
                .thenReturn(Uni.createFrom().item(Optional.empty()))
                .thenReturn(Uni.createFrom().item(Optional.of(mock(ServiceOnlyMatch.class))));

        final var missing = service.prepare(request()).await().atMost(Duration.ofSeconds(1));
        final var serviceOnly = service.prepare(request()).await().atMost(Duration.ofSeconds(1));

        assertInstanceOf(GatewayResult.RouteNotFound.class, ((ProxyPlan.Rejected) missing).result());
        assertInstanceOf(GatewayResult.RouteNotFound.class, ((ProxyPlan.Rejected) serviceOnly).result());
    }

    @Test
    void preparesTheRouteWithoutExecutingAnotherProxyPath() {
        final var route = route();
        final var prepared = new PreparedProxyRequest("GET", URI.create("http://backend/items"), Map.of(), null);
        when(registry.findRouteAsync(any(), any())).thenReturn(Uni.createFrom().item(Optional.of(route)));
        when(authentication.authenticate(any(), any()))
                .thenReturn(Uni.createFrom().item(new RouteAuthResult.NotRequired()));
        when(preparer.prepare(any(), any())).thenReturn(prepared);

        final var plan = assertInstanceOf(
                ProxyPlan.Ready.class, service.prepare(request()).await().atMost(Duration.ofSeconds(1)));

        assertEquals(prepared, plan.request());
        verify(preparer).prepare(any(), any());
    }

    @Test
    void usesTheResolvedRouteWithoutQueryingTheRegistryAgain() {
        final var route = route();
        final var prepared = new PreparedProxyRequest("GET", URI.create("http://backend/items"), Map.of(), null);
        when(authentication.authenticate(any(), same(route)))
                .thenReturn(Uni.createFrom().item(new RouteAuthResult.NotRequired()));
        when(preparer.prepare(any(), same(route))).thenReturn(prepared);

        final var plan = assertInstanceOf(
                ProxyPlan.Ready.class,
                service.prepare(request(Optional.of(route))).await().atMost(Duration.ofSeconds(1)));

        assertEquals(prepared, plan.request());
        verifyNoInteractions(registry);
    }

    @Test
    void usesAnEmptyRouteSnapshotWithoutQueryingTheRegistryAgain() {
        final var plan = assertInstanceOf(
                ProxyPlan.Rejected.class,
                service.prepare(request(Optional.empty())).await().atMost(Duration.ofSeconds(1)));

        assertInstanceOf(GatewayResult.RouteNotFound.class, plan.result());
        verifyNoInteractions(registry, authentication, preparer);
    }

    @Test
    void forwardsTheIssuedTokenIntoRequestPreparation() {
        final var route = route();
        final var token = new AussieToken("signed", "subject", Instant.now().plusSeconds(60), Map.of());
        final var prepared = new PreparedProxyRequest("GET", URI.create("http://backend/items"), Map.of(), null);
        when(registry.findRouteAsync(any(), any())).thenReturn(Uni.createFrom().item(Optional.of(route)));
        when(authentication.authenticate(any(), any()))
                .thenReturn(Uni.createFrom().item(new RouteAuthResult.Authenticated(token)));
        when(preparer.prepare(any(), any(), eq(Optional.of(token)))).thenReturn(prepared);

        final var plan = assertInstanceOf(
                ProxyPlan.Ready.class, service.prepare(request()).await().atMost(Duration.ofSeconds(1)));

        assertEquals(prepared, plan.request());
        verify(preparer).prepare(any(), any(), eq(Optional.of(token)));
    }

    @Test
    void preservesAuthenticationRejectionsInTheProxyPlan() {
        when(registry.findRouteAsync(any(), any())).thenReturn(Uni.createFrom().item(Optional.of(route())));
        when(authentication.authenticate(any(), any()))
                .thenReturn(Uni.createFrom().item(new RouteAuthResult.Unauthorized("expired")))
                .thenReturn(Uni.createFrom().item(new RouteAuthResult.Forbidden("denied")))
                .thenReturn(Uni.createFrom().item(new RouteAuthResult.BadRequest("conflicting credentials")));

        final var unauthorized =
                (ProxyPlan.Rejected) service.prepare(request()).await().atMost(Duration.ofSeconds(1));
        final var forbidden =
                (ProxyPlan.Rejected) service.prepare(request()).await().atMost(Duration.ofSeconds(1));
        final var badRequest =
                (ProxyPlan.Rejected) service.prepare(request()).await().atMost(Duration.ofSeconds(1));

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

    private RouteMatch route() {
        final var registration =
                ServiceRegistration.builder("backend").baseUrl("http://backend").build();
        final var endpoint =
                new EndpointConfig("/items", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), false);
        return new RouteMatch(registration, endpoint, "/items", Map.of());
    }
}
