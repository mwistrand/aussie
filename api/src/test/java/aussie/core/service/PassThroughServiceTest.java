package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.net.URI;
import java.util.List;
import java.util.Map;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import aussie.adapter.out.storage.NoOpConfigurationCache;
import aussie.adapter.out.storage.memory.InMemoryServiceRegistrationRepository;
import aussie.core.model.GatewayRequest;
import aussie.core.model.GatewayResult;
import aussie.core.model.GatewaySecurityConfig;
import aussie.core.model.PreparedProxyRequest;
import aussie.core.model.ProxyResponse;
import aussie.core.model.ServiceRegistration;
import aussie.core.port.out.ProxyClient;

@DisplayName("PassThroughService")
class PassThroughServiceTest {

    private static final java.time.Duration TIMEOUT = java.time.Duration.ofSeconds(5);
    private ServiceRegistry serviceRegistry;
    private ProxyRequestPreparer requestPreparer;
    private TestProxyClient proxyClient;
    private VisibilityResolver visibilityResolver;
    private PassThroughService passThroughService;

    // Permissive security config for testing
    private static final GatewaySecurityConfig PERMISSIVE_CONFIG = () -> true;

    @BeforeEach
    void setUp() {
        var validator = new ServiceRegistrationValidator(PERMISSIVE_CONFIG);
        serviceRegistry = new ServiceRegistry(
                new InMemoryServiceRegistrationRepository(), NoOpConfigurationCache.INSTANCE, validator);
        requestPreparer = new ProxyRequestPreparer(() -> (req, uri) -> Map.of());
        proxyClient = new TestProxyClient();
        visibilityResolver = new VisibilityResolver(new GlobPatternMatcher());
        passThroughService = new PassThroughService(serviceRegistry, requestPreparer, proxyClient, visibilityResolver);
    }

    private GatewayRequest createRequest(String method, String path) {
        return new GatewayRequest(method, path, Map.of(), URI.create("http://gateway:8080" + path), null);
    }

    private void registerService(String serviceId, String baseUrl) {
        var service = ServiceRegistration.builder(serviceId).baseUrl(baseUrl).build();
        serviceRegistry.register(service).await().atMost(TIMEOUT);
    }

    @Nested
    @DisplayName("Reserved Paths")
    class ReservedPathTests {

        @ParameterizedTest
        @ValueSource(strings = {"admin", "Admin", "ADMIN", "gateway", "Gateway", "GATEWAY", "q", "Q"})
        @DisplayName("Should return ReservedPath for reserved service IDs")
        void shouldReturnReservedPathForReservedServiceIds(String reservedPath) {
            var request = createRequest("GET", "/some/path");

            var result =
                    passThroughService.forward(reservedPath, request).await().indefinitely();

            assertInstanceOf(GatewayResult.ReservedPath.class, result);
            var reserved = (GatewayResult.ReservedPath) result;
            assertEquals(reservedPath, reserved.path());
        }

        @Test
        @DisplayName("Should not treat partial matches as reserved")
        void shouldNotTreatPartialMatchesAsReserved() {
            registerService("admin-service", "http://backend:9090");
            proxyClient.setResponse(new ProxyResponse(200, Map.of(), new byte[0]));

            var request = createRequest("GET", "/api/test");

            var result =
                    passThroughService.forward("admin-service", request).await().indefinitely();

            assertInstanceOf(GatewayResult.Success.class, result);
        }
    }

    @Nested
    @DisplayName("Service Lookup")
    class ServiceLookupTests {

        @Test
        @DisplayName("Should return ServiceNotFound when service does not exist")
        void shouldReturnServiceNotFoundWhenServiceDoesNotExist() {
            var request = createRequest("GET", "/api/test");

            var result = passThroughService
                    .forward("unknown-service", request)
                    .await()
                    .indefinitely();

            assertInstanceOf(GatewayResult.ServiceNotFound.class, result);
            var notFound = (GatewayResult.ServiceNotFound) result;
            assertEquals("unknown-service", notFound.serviceId());
        }

        @Test
        @DisplayName("Should find registered service")
        void shouldFindRegisteredService() {
            registerService("my-service", "http://backend:9090");
            proxyClient.setResponse(new ProxyResponse(200, Map.of(), new byte[0]));

            var request = createRequest("GET", "/api/test");

            var result =
                    passThroughService.forward("my-service", request).await().indefinitely();

            assertInstanceOf(GatewayResult.Success.class, result);
        }
    }

    @Nested
    @DisplayName("Request Forwarding")
    class RequestForwardingTests {

        @Test
        @DisplayName("Should forward request to correct target URI")
        void shouldForwardRequestToCorrectTargetUri() {
            registerService("my-service", "http://backend:9090");
            proxyClient.setResponse(new ProxyResponse(200, Map.of(), new byte[0]));

            var request = createRequest("GET", "/api/items/123");

            passThroughService.forward("my-service", request).await().indefinitely();

            var forwardedRequest = proxyClient.getLastRequest();
            assertEquals(URI.create("http://backend:9090/api/items/123"), forwardedRequest.targetUri());
        }

        @Test
        @DisplayName("Should forward request with correct method")
        void shouldForwardRequestWithCorrectMethod() {
            registerService("my-service", "http://backend:9090");
            proxyClient.setResponse(new ProxyResponse(201, Map.of(), new byte[0]));

            var request = createRequest("POST", "/api/items");

            passThroughService.forward("my-service", request).await().indefinitely();

            var forwardedRequest = proxyClient.getLastRequest();
            assertEquals("POST", forwardedRequest.method());
        }

        @Test
        @DisplayName("Should return Success with response data")
        void shouldReturnSuccessWithResponseData() {
            registerService("my-service", "http://backend:9090");
            proxyClient.setResponse(new ProxyResponse(
                    200, Map.of("Content-Type", List.of("application/json")), "{\"data\":\"test\"}".getBytes()));

            var request = createRequest("GET", "/api/data");

            var result =
                    passThroughService.forward("my-service", request).await().indefinitely();

            assertInstanceOf(GatewayResult.Success.class, result);
            var success = (GatewayResult.Success) result;
            assertEquals(200, success.statusCode());
            assertEquals(List.of("application/json"), success.headers().get("Content-Type"));
            assertArrayEquals("{\"data\":\"test\"}".getBytes(), success.body());
        }

        @Test
        @DisplayName("Should return Error when proxy fails")
        void shouldReturnErrorWhenProxyFails() {
            registerService("my-service", "http://backend:9090");
            proxyClient.setError(new RuntimeException("Connection timeout"));

            var request = createRequest("GET", "/api/test");

            var result =
                    passThroughService.forward("my-service", request).await().indefinitely();

            assertInstanceOf(GatewayResult.Error.class, result);
            var error = (GatewayResult.Error) result;
            assertEquals("Connection timeout", error.message());
        }
    }

    @Nested
    @DisplayName("Pass-Through Behavior")
    class PassThroughBehaviorTests {

        @Test
        @DisplayName("Should forward any HTTP method")
        void shouldForwardAnyHttpMethod() {
            registerService("my-service", "http://backend:9090");
            proxyClient.setResponse(new ProxyResponse(200, Map.of(), new byte[0]));

            var methods = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD");

            for (var method : methods) {
                var request = createRequest(method, "/api/test");
                var result = passThroughService
                        .forward("my-service", request)
                        .await()
                        .indefinitely();

                assertInstanceOf(GatewayResult.Success.class, result, "Should succeed for method: " + method);
            }
        }

        @Test
        @DisplayName("Should forward any path")
        void shouldForwardAnyPath() {
            registerService("my-service", "http://backend:9090");
            proxyClient.setResponse(new ProxyResponse(200, Map.of(), new byte[0]));

            var paths = List.of("/", "/api", "/api/nested/deeply/path", "/with-special_chars.json");

            for (var path : paths) {
                var request = createRequest("GET", path);
                var result = passThroughService
                        .forward("my-service", request)
                        .await()
                        .indefinitely();

                assertInstanceOf(GatewayResult.Success.class, result, "Should succeed for path: " + path);
            }
        }

        @Test
        @DisplayName("Should preserve path in target URI")
        void shouldPreservePathInTargetUri() {
            registerService("my-service", "http://backend:9090");
            proxyClient.setResponse(new ProxyResponse(200, Map.of(), new byte[0]));

            var request = createRequest("GET", "/api/users/123/orders");

            passThroughService.forward("my-service", request).await().indefinitely();

            var forwardedRequest = proxyClient.getLastRequest();
            assertEquals("/api/users/123/orders", forwardedRequest.targetUri().getPath());
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
