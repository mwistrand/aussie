package aussie.system.filter;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.model.common.SourceIdentifier;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.routing.ServiceOnlyMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.auth.AccessControlEvaluator;
import aussie.core.service.common.SourceIdentifierExtractor;
import aussie.core.service.routing.ServiceRegistry;

@DisplayName("AccessControlFilter")
@ExtendWith(MockitoExtension.class)
class AccessControlFilterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private ServiceRegistry serviceRegistry;

    @Mock
    private SourceIdentifierExtractor sourceExtractor;

    @Mock
    private AccessControlEvaluator accessEvaluator;

    @Mock
    private ContainerRequestContext requestContext;

    @Mock
    private HttpServerRequest vertxRequest;

    @Mock
    private UriInfo uriInfo;

    @Mock
    private SocketAddress socketAddress;

    private AccessControlFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AccessControlFilter(serviceRegistry, sourceExtractor, accessEvaluator);
    }

    private ServiceRegistration createService(String serviceId) {
        return ServiceRegistration.builder(serviceId)
                .displayName(serviceId)
                .baseUrl(URI.create("http://localhost:8080"))
                .defaultVisibility(EndpointVisibility.PUBLIC)
                .build();
    }

    private ServiceOnlyMatch createServiceOnlyMatch(ServiceRegistration service) {
        return new ServiceOnlyMatch(service);
    }

    private RouteMatch createRouteMatch(ServiceRegistration service) {
        var endpoint = EndpointConfig.publicEndpoint("/api/users", Set.of("GET"));
        return new RouteMatch(service, endpoint, "/api/users", Map.of());
    }

    private void setupPath(String path, String method) {
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn(path);
        when(requestContext.getMethod()).thenReturn(method);
    }

    private void setupSocketAddress(String host) {
        when(vertxRequest.remoteAddress()).thenReturn(socketAddress);
        when(socketAddress.host()).thenReturn(host);
    }

    @Nested
    @DisplayName("Gateway requests")
    class GatewayRequests {

        @Test
        @DisplayName("should allow request when route found and access allowed")
        void routeFoundAndAllowed() {
            setupPath("/gateway/api/users", "GET");
            setupSocketAddress("192.168.1.1");

            var service = createService("test-service");
            var routeResult = createRouteMatch(service);

            when(serviceRegistry.findRoute("/api/users", "GET")).thenReturn(Optional.of(routeResult));

            var source = SourceIdentifier.of("192.168.1.1");
            when(sourceExtractor.extract(requestContext, "192.168.1.1")).thenReturn(source);
            when(accessEvaluator.isAllowed(source, routeResult, service.accessConfig()))
                    .thenReturn(true);

            var result = filter.filter(requestContext, vertxRequest).await().atMost(TIMEOUT);

            assertNull(result);
        }

        @Test
        @DisplayName("should throw HttpProblem when route found but access denied")
        void routeFoundAndDenied() {
            setupPath("/gateway/api/users", "GET");
            setupSocketAddress("192.168.1.1");

            var service = createService("test-service");
            var routeResult = createRouteMatch(service);

            when(serviceRegistry.findRoute("/api/users", "GET")).thenReturn(Optional.of(routeResult));

            var source = SourceIdentifier.of("192.168.1.1");
            when(sourceExtractor.extract(requestContext, "192.168.1.1")).thenReturn(source);
            when(accessEvaluator.isAllowed(source, routeResult, service.accessConfig()))
                    .thenReturn(false);

            assertThrows(
                    HttpProblem.class,
                    () -> filter.filter(requestContext, vertxRequest).await().atMost(TIMEOUT));
        }

        @Test
        @DisplayName("should return null when no route found")
        void noRouteFound() {
            setupPath("/gateway/api/unknown", "GET");
            setupSocketAddress("192.168.1.1");

            when(serviceRegistry.findRoute("/api/unknown", "GET")).thenReturn(Optional.empty());

            var result = filter.filter(requestContext, vertxRequest).await().atMost(TIMEOUT);

            assertNull(result);
            verify(accessEvaluator, never()).isAllowed(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Pass-through requests")
    class PassThroughRequests {

        @Test
        @DisplayName("should return null for reserved path 'admin'")
        void reservedPathAdmin() {
            setupPath("/admin/something", "GET");
            setupSocketAddress("10.0.0.1");

            var result = filter.filter(requestContext, vertxRequest).await().atMost(TIMEOUT);

            assertNull(result);
            verify(serviceRegistry, never()).getService(any());
        }

        @Test
        @DisplayName("should return null for reserved path 'q'")
        void reservedPathQ() {
            setupPath("/q/dev", "GET");
            setupSocketAddress("10.0.0.1");

            var result = filter.filter(requestContext, vertxRequest).await().atMost(TIMEOUT);

            assertNull(result);
            verify(serviceRegistry, never()).getService(any());
        }

        @Test
        @DisplayName("should check access when service found with specific route")
        void serviceFoundWithRoute() {
            setupPath("/my-service/api/users", "GET");
            setupSocketAddress("10.0.0.1");

            var service = createService("my-service");
            var routeResult = createRouteMatch(service);

            when(serviceRegistry.getService("my-service"))
                    .thenReturn(Uni.createFrom().item(Optional.of(service)));
            when(serviceRegistry.findRoute("/api/users", "GET")).thenReturn(Optional.of(routeResult));

            var source = SourceIdentifier.of("10.0.0.1");
            when(sourceExtractor.extract(requestContext, "10.0.0.1")).thenReturn(source);
            when(accessEvaluator.isAllowed(source, routeResult, service.accessConfig()))
                    .thenReturn(true);

            var result = filter.filter(requestContext, vertxRequest).await().atMost(TIMEOUT);

            assertNull(result);
        }

        @Test
        @DisplayName("should use ServiceOnlyMatch when service found but no route match")
        void serviceFoundNoRouteMatch() {
            setupPath("/my-service/api/unknown", "GET");
            setupSocketAddress("10.0.0.1");

            var service = createService("my-service");

            when(serviceRegistry.getService("my-service"))
                    .thenReturn(Uni.createFrom().item(Optional.of(service)));
            when(serviceRegistry.findRoute("/api/unknown", "GET")).thenReturn(Optional.empty());

            var source = SourceIdentifier.of("10.0.0.1");
            when(sourceExtractor.extract(eq(requestContext), eq("10.0.0.1"))).thenReturn(source);
            when(accessEvaluator.isAllowed(eq(source), any(ServiceOnlyMatch.class), eq(service.accessConfig())))
                    .thenReturn(true);

            var result = filter.filter(requestContext, vertxRequest).await().atMost(TIMEOUT);

            assertNull(result);
        }

        @Test
        @DisplayName("should return null when service not found")
        void serviceNotFound() {
            setupPath("/unknown-service/api/users", "GET");
            setupSocketAddress("10.0.0.1");

            when(serviceRegistry.getService("unknown-service"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            var result = filter.filter(requestContext, vertxRequest).await().atMost(TIMEOUT);

            assertNull(result);
            verify(accessEvaluator, never()).isAllowed(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Socket address handling")
    class SocketAddressHandling {

        @Test
        @DisplayName("should set socketIp to null when remoteAddress is null")
        void nullRemoteAddress() {
            setupPath("/gateway/api/users", "GET");
            when(vertxRequest.remoteAddress()).thenReturn(null);

            when(serviceRegistry.findRoute("/api/users", "GET")).thenReturn(Optional.empty());

            var result = filter.filter(requestContext, vertxRequest).await().atMost(TIMEOUT);

            assertNull(result);
        }
    }
}
