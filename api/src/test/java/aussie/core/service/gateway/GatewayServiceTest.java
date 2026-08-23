package aussie.core.service.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.AussieToken;
import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.PreparedProxyRequest;
import aussie.core.model.gateway.ProxyResponse;
import aussie.core.model.gateway.RouteAuthResult;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.routing.ServiceOnlyMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.AuthenticatedContext;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.ProxyClient;
import aussie.core.port.out.SecurityMonitoring;
import aussie.core.port.out.TrafficAttributing;
import aussie.core.service.routing.ServiceRegistry;

@DisplayName("GatewayService")
class GatewayServiceTest {

    private ServiceRegistry serviceRegistry;
    private ProxyRequestPreparer requestPreparer;
    private ProxyClient proxyClient;
    private RouteAuthenticationService routeAuthService;
    private Metrics metrics;
    private SecurityMonitoring securityMonitor;
    private TrafficAttributing attributionService;
    private AuthenticatedContext authenticatedContext;
    private GatewayService gatewayService;

    @BeforeEach
    void setUp() {
        serviceRegistry = mock(ServiceRegistry.class);
        requestPreparer = mock(ProxyRequestPreparer.class);
        proxyClient = mock(ProxyClient.class);
        routeAuthService = mock(RouteAuthenticationService.class);
        metrics = mock(Metrics.class);
        securityMonitor = mock(SecurityMonitoring.class);
        attributionService = mock(TrafficAttributing.class);
        authenticatedContext = mock(AuthenticatedContext.class);

        gatewayService = new GatewayService(
                serviceRegistry,
                requestPreparer,
                proxyClient,
                routeAuthService,
                metrics,
                securityMonitor,
                attributionService,
                authenticatedContext);
    }

    private ServiceRegistration testService() {
        return ServiceRegistration.builder("test-service")
                .displayName("Test")
                .baseUrl(URI.create("http://backend:8080"))
                .build();
    }

    private GatewayRequest testRequest() {
        return new GatewayRequest(
                "GET", "/api/test", Map.of(), URI.create("http://localhost/api/test"), null, "127.0.0.1");
    }

    private RouteMatch testRouteMatch() {
        var endpoint =
                new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), false);
        return new RouteMatch(testService(), endpoint, "/api/test", Map.of());
    }

    @Nested
    @DisplayName("forward()")
    class ForwardTests {

        @Test
        @DisplayName("should return RouteNotFound when no route matches")
        void shouldReturnRouteNotFoundWhenNoRouteMatches() {
            when(serviceRegistry.findRouteAsync(anyString(), anyString()))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = gatewayService.forward(testRequest()).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(GatewayResult.RouteNotFound.class, result);
            verify(metrics).recordGatewayResult(any(), any());
        }

        @Test
        @DisplayName("should return RouteNotFound for ServiceOnlyMatch")
        void shouldReturnRouteNotFoundForServiceOnlyMatch() {
            var serviceOnly = mock(ServiceOnlyMatch.class);
            when(serviceRegistry.findRouteAsync(anyString(), anyString()))
                    .thenReturn(Uni.createFrom().item(Optional.of(serviceOnly)));

            var result = gatewayService.forward(testRequest()).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(GatewayResult.RouteNotFound.class, result);
        }

        @Test
        @DisplayName("should forward unauthenticated request for public route")
        void shouldForwardUnauthenticatedRequestForPublicRoute() {
            var routeMatch = testRouteMatch();
            var preparedRequest =
                    new PreparedProxyRequest("GET", URI.create("http://backend:8080/api/test"), Map.of(), null);
            var proxyResponse = new ProxyResponse(200, Map.of(), "OK".getBytes());

            when(serviceRegistry.findRouteAsync(anyString(), anyString()))
                    .thenReturn(Uni.createFrom().item(Optional.of(routeMatch)));
            when(routeAuthService.authenticate(any(), any()))
                    .thenReturn(Uni.createFrom().item(new RouteAuthResult.NotRequired()));
            when(requestPreparer.prepare(any(), any(), any())).thenReturn(preparedRequest);
            when(proxyClient.forward(preparedRequest))
                    .thenReturn(Uni.createFrom().item(proxyResponse));
            when(attributionService.isEnabled()).thenReturn(false);

            var result = gatewayService.forward(testRequest()).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(GatewayResult.Success.class, result);
            assertEquals(200, ((GatewayResult.Success) result).statusCode());
        }

        @Test
        @DisplayName("should forward authenticated request with token")
        void shouldForwardAuthenticatedRequestWithToken() {
            var routeMatch = testRouteMatch();
            var aussieToken =
                    new AussieToken("signed-token", "user-1", Instant.now().plusSeconds(300), Map.of());
            var preparedRequest =
                    new PreparedProxyRequest("GET", URI.create("http://backend:8080/api/test"), Map.of(), null);
            var proxyResponse = new ProxyResponse(200, Map.of(), "OK".getBytes());

            when(serviceRegistry.findRouteAsync(anyString(), anyString()))
                    .thenReturn(Uni.createFrom().item(Optional.of(routeMatch)));
            when(routeAuthService.authenticate(any(), any()))
                    .thenReturn(Uni.createFrom().item(new RouteAuthResult.Authenticated(aussieToken)));
            when(requestPreparer.prepare(any(), any(), any())).thenReturn(preparedRequest);
            when(proxyClient.forward(preparedRequest))
                    .thenReturn(Uni.createFrom().item(proxyResponse));
            when(attributionService.isEnabled()).thenReturn(false);

            var result = gatewayService.forward(testRequest()).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(GatewayResult.Success.class, result);
        }

        @Test
        @DisplayName("should return Unauthorized when auth fails")
        void shouldReturnUnauthorizedWhenAuthFails() {
            var routeMatch = testRouteMatch();

            when(serviceRegistry.findRouteAsync(anyString(), anyString()))
                    .thenReturn(Uni.createFrom().item(Optional.of(routeMatch)));
            when(routeAuthService.authenticate(any(), any()))
                    .thenReturn(Uni.createFrom().item(new RouteAuthResult.Unauthorized("token expired")));
            when(attributionService.isEnabled()).thenReturn(false);

            var result = gatewayService.forward(testRequest()).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(GatewayResult.Unauthorized.class, result);
            assertEquals("token expired", ((GatewayResult.Unauthorized) result).reason());
        }

        @Test
        @DisplayName("should return Forbidden when access denied")
        void shouldReturnForbiddenWhenAccessDenied() {
            var routeMatch = testRouteMatch();

            when(serviceRegistry.findRouteAsync(anyString(), anyString()))
                    .thenReturn(Uni.createFrom().item(Optional.of(routeMatch)));
            when(routeAuthService.authenticate(any(), any()))
                    .thenReturn(Uni.createFrom().item(new RouteAuthResult.Forbidden("insufficient permissions")));
            when(attributionService.isEnabled()).thenReturn(false);

            var result = gatewayService.forward(testRequest()).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(GatewayResult.Forbidden.class, result);
        }

        @Test
        @DisplayName("should return BadRequest for conflicting auth")
        void shouldReturnBadRequestForConflictingAuth() {
            var routeMatch = testRouteMatch();

            when(serviceRegistry.findRouteAsync(anyString(), anyString()))
                    .thenReturn(Uni.createFrom().item(Optional.of(routeMatch)));
            when(routeAuthService.authenticate(any(), any()))
                    .thenReturn(Uni.createFrom().item(new RouteAuthResult.BadRequest("conflicting auth")));
            when(attributionService.isEnabled()).thenReturn(false);

            var result = gatewayService.forward(testRequest()).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(GatewayResult.BadRequest.class, result);
        }

        @Test
        @DisplayName("should return Error when proxy fails")
        void shouldReturnErrorWhenProxyFails() {
            var routeMatch = testRouteMatch();
            var preparedRequest =
                    new PreparedProxyRequest("GET", URI.create("http://backend:8080/api/test"), Map.of(), null);

            when(serviceRegistry.findRouteAsync(anyString(), anyString()))
                    .thenReturn(Uni.createFrom().item(Optional.of(routeMatch)));
            when(routeAuthService.authenticate(any(), any()))
                    .thenReturn(Uni.createFrom().item(new RouteAuthResult.NotRequired()));
            when(requestPreparer.prepare(any(), any(), any())).thenReturn(preparedRequest);
            when(proxyClient.forward(preparedRequest))
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("connection refused")));
            when(attributionService.isEnabled()).thenReturn(false);

            var result = gatewayService.forward(testRequest()).await().atMost(Duration.ofSeconds(1));

            assertInstanceOf(GatewayResult.Error.class, result);
            assertEquals("Upstream request failed", ((GatewayResult.Error) result).message());
        }
    }
}
