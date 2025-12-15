package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.storage.NoOpConfigurationCache;
import aussie.adapter.out.storage.memory.InMemoryServiceRegistrationRepository;
import aussie.core.cache.LocalCacheConfig;
import aussie.core.model.auth.GatewaySecurityConfig;
import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.PreparedProxyRequest;
import aussie.core.model.gateway.ProxyResponse;
import aussie.core.model.gateway.RouteAuthResult;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.ProxyClient;
import aussie.core.port.out.SecurityMonitoring;
import aussie.core.port.out.TrafficAttributing;
import aussie.core.service.auth.*;
import aussie.core.service.gateway.*;
import aussie.core.service.routing.*;

@DisplayName("GatewayService")
class GatewayServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private ServiceRegistry serviceRegistry;
    private ProxyRequestPreparer requestPreparer;
    private TestProxyClient proxyClient;
    private RouteAuthenticationService routeAuthService;
    private Metrics metrics;
    private SecurityMonitoring securityMonitor;
    private TrafficAttributing attributionService;
    private GatewayService gatewayService;

    // Permissive security config for testing
    private static final GatewaySecurityConfig PERMISSIVE_CONFIG = () -> true;

    // Test cache config
    private static final LocalCacheConfig TEST_CACHE_CONFIG = new LocalCacheConfig() {
        @Override
        public Duration serviceRoutesTtl() {
            return Duration.ofMinutes(5);
        }

        @Override
        public Duration rateLimitConfigTtl() {
            return Duration.ofMinutes(5);
        }

        @Override
        public long maxEntries() {
            return 1000;
        }

        @Override
        public double jitterFactor() {
            return 0.0; // No jitter in tests for predictable behavior
        }
    };

    @BeforeEach
    void setUp() {
        var validator = new ServiceRegistrationValidator(PERMISSIVE_CONFIG);
        var defaultPolicy = new DefaultPermissionPolicy();
        var authService = new ServiceAuthorizationService(defaultPolicy);
        serviceRegistry = new ServiceRegistry(
                new InMemoryServiceRegistrationRepository(),
                NoOpConfigurationCache.INSTANCE,
                validator,
                authService,
                TEST_CACHE_CONFIG);
        requestPreparer = new ProxyRequestPreparer(() -> (req, uri) -> Map.of());
        proxyClient = new TestProxyClient();
        routeAuthService = new NoOpRouteAuthService();
        metrics = new NoOpMetrics();
        securityMonitor = new NoOpSecurityMonitoring();
        attributionService = new NoOpTrafficAttributing();
        gatewayService = new GatewayService(
                serviceRegistry,
                requestPreparer,
                proxyClient,
                routeAuthService,
                metrics,
                securityMonitor,
                attributionService);
    }

    private GatewayRequest createRequest(String method, String path) {
        return new GatewayRequest(method, path, Map.of(), URI.create("http://gateway:8080" + path), null);
    }

    private void registerService(String serviceId, String baseUrl, String endpointPath, Set<String> methods) {
        var endpoint = new EndpointConfig(endpointPath, methods, EndpointVisibility.PUBLIC, Optional.empty());
        var service = ServiceRegistration.builder(serviceId)
                .baseUrl(baseUrl)
                .endpoints(List.of(endpoint))
                .build();
        serviceRegistry.register(service).await().atMost(TIMEOUT);
    }

    @Nested
    @DisplayName("forward()")
    class ForwardTests {

        @Test
        @DisplayName("Should return RouteNotFound when no matching route exists")
        void shouldReturnRouteNotFoundWhenNoMatchingRoute() {
            var request = createRequest("GET", "/api/unknown");

            var result = gatewayService.forward(request).await().indefinitely();

            assertInstanceOf(GatewayResult.RouteNotFound.class, result);
            var routeNotFound = (GatewayResult.RouteNotFound) result;
            assertEquals("/api/unknown", routeNotFound.path());
        }

        @Test
        @DisplayName("Should return RouteNotFound when method does not match")
        void shouldReturnRouteNotFoundWhenMethodDoesNotMatch() {
            registerService("test-service", "http://backend:9090", "/api/test", Set.of("GET"));
            var request = createRequest("POST", "/api/test");

            var result = gatewayService.forward(request).await().indefinitely();

            assertInstanceOf(GatewayResult.RouteNotFound.class, result);
        }

        @Test
        @DisplayName("Should return Success when route matches and proxy succeeds")
        void shouldReturnSuccessWhenRouteMatchesAndProxySucceeds() {
            registerService("test-service", "http://backend:9090", "/api/test", Set.of("GET"));
            proxyClient.setResponse(
                    new ProxyResponse(200, Map.of("Content-Type", List.of("application/json")), "response".getBytes()));

            var request = createRequest("GET", "/api/test");

            var result = gatewayService.forward(request).await().indefinitely();

            assertInstanceOf(GatewayResult.Success.class, result);
            var success = (GatewayResult.Success) result;
            assertEquals(200, success.statusCode());
            assertEquals(List.of("application/json"), success.headers().get("Content-Type"));
            assertArrayEquals("response".getBytes(), success.body());
        }

        @Test
        @DisplayName("Should return Error when proxy fails")
        void shouldReturnErrorWhenProxyFails() {
            registerService("test-service", "http://backend:9090", "/api/test", Set.of("GET"));
            proxyClient.setError(new RuntimeException("Connection refused"));

            var request = createRequest("GET", "/api/test");

            var result = gatewayService.forward(request).await().indefinitely();

            assertInstanceOf(GatewayResult.Error.class, result);
            var error = (GatewayResult.Error) result;
            assertEquals("Connection refused", error.message());
        }

        @Test
        @DisplayName("Should forward request with correct target URI")
        void shouldForwardRequestWithCorrectTargetUri() {
            registerService("test-service", "http://backend:9090", "/api/items", Set.of("GET"));
            proxyClient.setResponse(new ProxyResponse(200, Map.of(), new byte[0]));

            var request = createRequest("GET", "/api/items");

            gatewayService.forward(request).await().indefinitely();

            var forwardedRequest = proxyClient.getLastRequest();
            assertEquals(URI.create("http://backend:9090/api/items"), forwardedRequest.targetUri());
        }

        @Test
        @DisplayName("Should forward request with correct method")
        void shouldForwardRequestWithCorrectMethod() {
            registerService("test-service", "http://backend:9090", "/api/items", Set.of("POST"));
            proxyClient.setResponse(new ProxyResponse(201, Map.of(), new byte[0]));

            var request = createRequest("POST", "/api/items");

            gatewayService.forward(request).await().indefinitely();

            var forwardedRequest = proxyClient.getLastRequest();
            assertEquals("POST", forwardedRequest.method());
        }

        @Test
        @DisplayName("Should match wildcard method endpoint")
        void shouldMatchWildcardMethodEndpoint() {
            var endpoint = new EndpointConfig("/api/all", Set.of("*"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://backend:9090")
                    .endpoints(List.of(endpoint))
                    .build();
            serviceRegistry.register(service).await().atMost(TIMEOUT);
            proxyClient.setResponse(new ProxyResponse(200, Map.of(), new byte[0]));

            var getResult = gatewayService
                    .forward(createRequest("GET", "/api/all"))
                    .await()
                    .indefinitely();
            var postResult = gatewayService
                    .forward(createRequest("POST", "/api/all"))
                    .await()
                    .indefinitely();
            var deleteResult = gatewayService
                    .forward(createRequest("DELETE", "/api/all"))
                    .await()
                    .indefinitely();

            assertInstanceOf(GatewayResult.Success.class, getResult);
            assertInstanceOf(GatewayResult.Success.class, postResult);
            assertInstanceOf(GatewayResult.Success.class, deleteResult);
        }
    }

    @Nested
    @DisplayName("Path Variable Handling")
    class PathVariableTests {

        @Test
        @DisplayName("Should match path with variables")
        void shouldMatchPathWithVariables() {
            var endpoint = new EndpointConfig(
                    "/api/users/{userId}", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://backend:9090")
                    .endpoints(List.of(endpoint))
                    .build();
            serviceRegistry.register(service).await().atMost(TIMEOUT);
            proxyClient.setResponse(new ProxyResponse(200, Map.of(), new byte[0]));

            var request = createRequest("GET", "/api/users/123");

            var result = gatewayService.forward(request).await().indefinitely();

            assertInstanceOf(GatewayResult.Success.class, result);
        }

        @Test
        @DisplayName("Should match path with multiple variables")
        void shouldMatchPathWithMultipleVariables() {
            var endpoint = new EndpointConfig(
                    "/api/users/{userId}/orders/{orderId}", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://backend:9090")
                    .endpoints(List.of(endpoint))
                    .build();
            serviceRegistry.register(service).await().atMost(TIMEOUT);
            proxyClient.setResponse(new ProxyResponse(200, Map.of(), new byte[0]));

            var request = createRequest("GET", "/api/users/123/orders/456");

            var result = gatewayService.forward(request).await().indefinitely();

            assertInstanceOf(GatewayResult.Success.class, result);
            var forwardedRequest = proxyClient.getLastRequest();
            assertEquals(URI.create("http://backend:9090/api/users/123/orders/456"), forwardedRequest.targetUri());
        }
    }

    private static class TestProxyClient implements ProxyClient {
        private ProxyResponse response = new ProxyResponse(200, Map.of(), new byte[0]);
        private Throwable error = null;
        private PreparedProxyRequest lastRequest = null;

        void setResponse(ProxyResponse response) {
            this.response = response;
            this.error = null;
        }

        void setError(Throwable error) {
            this.error = error;
            this.response = null;
        }

        PreparedProxyRequest getLastRequest() {
            return lastRequest;
        }

        @Override
        public Uni<ProxyResponse> forward(PreparedProxyRequest request) {
            this.lastRequest = request;
            if (error != null) {
                return Uni.createFrom().failure(error);
            }
            return Uni.createFrom().item(response);
        }
    }

    /**
     * A no-op route authentication service that always allows requests (returns NotRequired).
     * Used for testing GatewayService without real authentication.
     */
    private static class NoOpRouteAuthService extends RouteAuthenticationService {
        NoOpRouteAuthService() {
            super(null, null, null, null, null);
        }

        @Override
        public Uni<RouteAuthResult> authenticate(GatewayRequest request, RouteMatch route) {
            return Uni.createFrom().item(new RouteAuthResult.NotRequired());
        }
    }

    /**
     * No-op metrics for testing without real metric recording.
     */
    private static class NoOpMetrics implements Metrics {
        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public void recordRequest(String serviceId, String method, int statusCode) {}

        @Override
        public void recordProxyLatency(String serviceId, String method, int statusCode, long latencyMs) {}

        @Override
        public void recordGatewayResult(String serviceId, GatewayResult result) {}

        @Override
        public void recordTraffic(String serviceId, String teamId, long requestBytes, long responseBytes) {}

        @Override
        public void recordError(String serviceId, String errorType) {}

        @Override
        public void recordAuthFailure(String reason, String clientIpHash) {}

        @Override
        public void recordAuthSuccess(String method) {}

        @Override
        public void recordAccessDenied(String serviceId, String reason) {}

        @Override
        public void incrementActiveConnections() {}

        @Override
        public void decrementActiveConnections() {}

        @Override
        public void incrementActiveWebSockets() {}

        @Override
        public void decrementActiveWebSockets() {}

        @Override
        public void recordWebSocketConnect(String serviceId) {}

        @Override
        public void recordWebSocketDisconnect(String serviceId, long durationMs) {}

        @Override
        public void recordWebSocketLimitReached() {}

        @Override
        public void recordRateLimitCheck(String serviceId, boolean allowed, long remaining) {}

        @Override
        public void recordRateLimitExceeded(String serviceId, String limitType) {}
    }

    /**
     * No-op security monitor for testing.
     */
    private static class NoOpSecurityMonitoring implements SecurityMonitoring {
        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public void recordRequest(String clientIp, String serviceId, boolean isError) {}

        @Override
        public void recordAuthFailure(String clientIp, String reason, String method) {}

        @Override
        public void recordAccessDenied(String clientIp, String serviceId, String path, String reason) {}
    }

    /**
     * No-op traffic attribution for testing.
     */
    private static class NoOpTrafficAttributing implements TrafficAttributing {
        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public void record(
                GatewayRequest request,
                ServiceRegistration service,
                long requestBodySize,
                long responseBodySize,
                long durationMs) {}
    }
}
