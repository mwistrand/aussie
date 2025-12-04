package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.EndpointConfig;
import aussie.core.model.EndpointVisibility;
import aussie.core.model.GatewayRequest;
import aussie.core.model.GatewayResult;
import aussie.core.model.PreparedProxyRequest;
import aussie.core.model.ProxyResponse;
import aussie.core.model.ServiceRegistration;
import aussie.core.port.out.ProxyClient;

@DisplayName("GatewayService")
class GatewayServiceTest {

    private ServiceRegistry serviceRegistry;
    private ProxyRequestPreparer requestPreparer;
    private TestProxyClient proxyClient;
    private GatewayService gatewayService;

    @BeforeEach
    void setUp() {
        serviceRegistry = new ServiceRegistry();
        requestPreparer = new ProxyRequestPreparer(() -> (req, uri) -> Map.of());
        proxyClient = new TestProxyClient();
        gatewayService = new GatewayService(serviceRegistry, requestPreparer, proxyClient);
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
        serviceRegistry.register(service);
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
            serviceRegistry.register(service);
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
            serviceRegistry.register(service);
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
            serviceRegistry.register(service);
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
}
