package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import aussie.core.model.gateway.RouteAuthResult;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointType;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.model.websocket.WebSocketUpgradeRequest;
import aussie.core.model.websocket.WebSocketUpgradeResult;
import aussie.core.service.auth.*;
import aussie.core.service.gateway.*;
import aussie.core.service.routing.*;

@DisplayName("WebSocketGatewayService")
class WebSocketGatewayServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private ServiceRegistry serviceRegistry;
    private RouteAuthenticationService routeAuthService;
    private EndpointMatcher endpointMatcher;
    private WebSocketGatewayService webSocketGatewayService;

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
        routeAuthService = new NoOpRouteAuthService();
        endpointMatcher = new EndpointMatcher(new GlobPatternMatcher());
        // Connection rate limiting is handled by WebSocketRateLimitFilter, not the service
        webSocketGatewayService = new WebSocketGatewayService(serviceRegistry, routeAuthService, endpointMatcher);
    }

    private WebSocketUpgradeRequest createRequest(String path) {
        return new WebSocketUpgradeRequest(
                path,
                Map.of("Upgrade", List.of("websocket"), "Connection", List.of("Upgrade")),
                URI.create("http://gateway:8080" + path));
    }

    private void registerWebSocketService(String serviceId, String baseUrl, String endpointPath, boolean authRequired) {
        var endpoint = new EndpointConfig(
                endpointPath,
                Set.of("GET"),
                EndpointVisibility.PUBLIC,
                Optional.empty(),
                authRequired,
                EndpointType.WEBSOCKET);
        var service = ServiceRegistration.builder(serviceId)
                .baseUrl(baseUrl)
                .endpoints(List.of(endpoint))
                .build();
        serviceRegistry.register(service).await().atMost(TIMEOUT);
    }

    private void registerHttpService(String serviceId, String baseUrl, String endpointPath) {
        var endpoint = new EndpointConfig(
                endpointPath, Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), false, EndpointType.HTTP);
        var service = ServiceRegistration.builder(serviceId)
                .baseUrl(baseUrl)
                .endpoints(List.of(endpoint))
                .build();
        serviceRegistry.register(service).await().atMost(TIMEOUT);
    }

    @Nested
    @DisplayName("upgradeGateway()")
    class UpgradeGatewayTests {

        @Test
        @DisplayName("Should return RouteNotFound when no matching route exists")
        void shouldReturnRouteNotFoundWhenNoMatchingRoute() {
            var request = createRequest("/ws/unknown");

            var result = webSocketGatewayService.upgradeGateway(request).await().atMost(TIMEOUT);

            assertInstanceOf(WebSocketUpgradeResult.RouteNotFound.class, result);
            var routeNotFound = (WebSocketUpgradeResult.RouteNotFound) result;
            assertEquals("/ws/unknown", routeNotFound.path());
        }

        @Test
        @DisplayName("Should return NotWebSocket when endpoint is HTTP type")
        void shouldReturnNotWebSocketWhenEndpointIsHttpType() {
            registerHttpService("test-service", "http://backend:9090", "/api/test");

            var request = createRequest("/api/test");

            var result = webSocketGatewayService.upgradeGateway(request).await().atMost(TIMEOUT);

            assertInstanceOf(WebSocketUpgradeResult.NotWebSocket.class, result);
        }

        @Test
        @DisplayName("Should return Authorized for valid WebSocket endpoint without auth")
        void shouldReturnAuthorizedForValidWebSocketEndpoint() {
            registerWebSocketService("test-service", "http://backend:9090", "/ws/echo", false);

            var request = createRequest("/ws/echo");

            var result = webSocketGatewayService.upgradeGateway(request).await().atMost(TIMEOUT);

            assertInstanceOf(WebSocketUpgradeResult.Authorized.class, result);
            var authorized = (WebSocketUpgradeResult.Authorized) result;
            assertEquals(URI.create("ws://backend:9090/ws/echo"), authorized.backendUri());
            assertTrue(authorized.token().isEmpty());
        }

        @Test
        @DisplayName("Should convert HTTPS backend URL to WSS")
        void shouldConvertHttpsToWss() {
            registerWebSocketService("test-service", "https://backend:9090", "/ws/secure", false);

            var request = createRequest("/ws/secure");

            var result = webSocketGatewayService.upgradeGateway(request).await().atMost(TIMEOUT);

            assertInstanceOf(WebSocketUpgradeResult.Authorized.class, result);
            var authorized = (WebSocketUpgradeResult.Authorized) result;
            assertEquals("wss", authorized.backendUri().getScheme());
        }

        @Test
        @DisplayName("Should handle backend URL without explicit port (HTTP)")
        void shouldHandleBackendUrlWithoutPortHttp() {
            registerWebSocketService("test-service", "http://backend", "/ws/echo", false);

            var request = createRequest("/ws/echo");

            var result = webSocketGatewayService.upgradeGateway(request).await().atMost(TIMEOUT);

            assertInstanceOf(WebSocketUpgradeResult.Authorized.class, result);
            var authorized = (WebSocketUpgradeResult.Authorized) result;
            assertEquals("ws://backend/ws/echo", authorized.backendUri().toString());
        }

        @Test
        @DisplayName("Should handle backend URL without explicit port (HTTPS)")
        void shouldHandleBackendUrlWithoutPortHttps() {
            registerWebSocketService("test-service", "https://backend", "/ws/secure", false);

            var request = createRequest("/ws/secure");

            var result = webSocketGatewayService.upgradeGateway(request).await().atMost(TIMEOUT);

            assertInstanceOf(WebSocketUpgradeResult.Authorized.class, result);
            var authorized = (WebSocketUpgradeResult.Authorized) result;
            assertEquals("wss://backend/ws/secure", authorized.backendUri().toString());
        }

        @Test
        @DisplayName("Should match wildcard path patterns")
        void shouldMatchWildcardPathPatterns() {
            var endpoint = new EndpointConfig(
                    "/ws/**",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.WEBSOCKET);
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://backend:9090")
                    .endpoints(List.of(endpoint))
                    .build();
            serviceRegistry.register(service).await().atMost(TIMEOUT);

            var request = createRequest("/ws/room/123");

            var result = webSocketGatewayService.upgradeGateway(request).await().atMost(TIMEOUT);

            assertInstanceOf(WebSocketUpgradeResult.Authorized.class, result);
        }
    }

    @Nested
    @DisplayName("upgradePassThrough()")
    class UpgradePassThroughTests {

        @Test
        @DisplayName("Should return ServiceNotFound when service does not exist")
        void shouldReturnServiceNotFoundWhenServiceDoesNotExist() {
            var request = createRequest("/ws/echo");

            var result = webSocketGatewayService
                    .upgradePassThrough("unknown-service", request)
                    .await()
                    .atMost(TIMEOUT);

            assertInstanceOf(WebSocketUpgradeResult.ServiceNotFound.class, result);
            var serviceNotFound = (WebSocketUpgradeResult.ServiceNotFound) result;
            assertEquals("unknown-service", serviceNotFound.serviceId());
        }

        @Test
        @DisplayName("Should return NotWebSocket when no WebSocket endpoint matches")
        void shouldReturnNotWebSocketWhenNoWebSocketEndpointMatches() {
            registerHttpService("test-service", "http://backend:9090", "/api/test");

            var request = createRequest("/api/test");

            var result = webSocketGatewayService
                    .upgradePassThrough("test-service", request)
                    .await()
                    .atMost(TIMEOUT);

            assertInstanceOf(WebSocketUpgradeResult.NotWebSocket.class, result);
        }

        @Test
        @DisplayName("Should return Authorized for valid WebSocket endpoint")
        void shouldReturnAuthorizedForValidWebSocketEndpoint() {
            registerWebSocketService("test-service", "http://backend:9090", "/ws/echo", false);

            var request = createRequest("/ws/echo");

            var result = webSocketGatewayService
                    .upgradePassThrough("test-service", request)
                    .await()
                    .atMost(TIMEOUT);

            assertInstanceOf(WebSocketUpgradeResult.Authorized.class, result);
            var authorized = (WebSocketUpgradeResult.Authorized) result;
            assertEquals(URI.create("ws://backend:9090/ws/echo"), authorized.backendUri());
        }

        @Test
        @DisplayName("Should return NotWebSocket when path does not match any endpoint")
        void shouldReturnNotWebSocketWhenPathDoesNotMatch() {
            registerWebSocketService("test-service", "http://backend:9090", "/ws/echo", false);

            var request = createRequest("/ws/chat");

            var result = webSocketGatewayService
                    .upgradePassThrough("test-service", request)
                    .await()
                    .atMost(TIMEOUT);

            assertInstanceOf(WebSocketUpgradeResult.NotWebSocket.class, result);
        }
    }

    @Nested
    @DisplayName("Authentication Integration")
    class AuthenticationTests {

        @Test
        @DisplayName("Should return Unauthorized when auth fails")
        void shouldReturnUnauthorizedWhenAuthFails() {
            var failingAuthService = new FailingAuthService("Token expired");
            webSocketGatewayService = new WebSocketGatewayService(serviceRegistry, failingAuthService, endpointMatcher);

            registerWebSocketService("test-service", "http://backend:9090", "/ws/protected", true);

            var request = createRequest("/ws/protected");

            var result = webSocketGatewayService.upgradeGateway(request).await().atMost(TIMEOUT);

            assertInstanceOf(WebSocketUpgradeResult.Unauthorized.class, result);
            var unauthorized = (WebSocketUpgradeResult.Unauthorized) result;
            assertEquals("Token expired", unauthorized.reason());
        }

        @Test
        @DisplayName("Should return Forbidden when access denied")
        void shouldReturnForbiddenWhenAccessDenied() {
            var forbiddingAuthService = new ForbiddingAuthService("Insufficient permissions");
            webSocketGatewayService =
                    new WebSocketGatewayService(serviceRegistry, forbiddingAuthService, endpointMatcher);

            registerWebSocketService("test-service", "http://backend:9090", "/ws/admin", true);

            var request = createRequest("/ws/admin");

            var result = webSocketGatewayService.upgradeGateway(request).await().atMost(TIMEOUT);

            assertInstanceOf(WebSocketUpgradeResult.Forbidden.class, result);
            var forbidden = (WebSocketUpgradeResult.Forbidden) result;
            assertEquals("Insufficient permissions", forbidden.reason());
        }
    }

    @Nested
    @DisplayName("EndpointType Defaults")
    class EndpointTypeDefaultsTests {

        @Test
        @DisplayName("Should treat endpoints without type as HTTP (default)")
        void shouldTreatEndpointsWithoutTypeAsHttp() {
            // Register using the constructor that defaults to HTTP
            var endpoint =
                    new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), false);
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://backend:9090")
                    .endpoints(List.of(endpoint))
                    .build();
            serviceRegistry.register(service).await().atMost(TIMEOUT);

            var request = createRequest("/api/users");

            var result = webSocketGatewayService.upgradeGateway(request).await().atMost(TIMEOUT);

            // Should fail because default type is HTTP, not WEBSOCKET
            assertInstanceOf(WebSocketUpgradeResult.NotWebSocket.class, result);
        }
    }

    /**
     * A no-op route authentication service that always allows requests (returns NotRequired).
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
     * A route authentication service that always returns Unauthorized.
     */
    private static class FailingAuthService extends RouteAuthenticationService {
        private final String reason;

        FailingAuthService(String reason) {
            super(null, null, null, null, null);
            this.reason = reason;
        }

        @Override
        public Uni<RouteAuthResult> authenticate(GatewayRequest request, RouteMatch route) {
            return Uni.createFrom().item(new RouteAuthResult.Unauthorized(reason));
        }
    }

    /**
     * A route authentication service that always returns Forbidden.
     */
    private static class ForbiddingAuthService extends RouteAuthenticationService {
        private final String reason;

        ForbiddingAuthService(String reason) {
            super(null, null, null, null, null);
            this.reason = reason;
        }

        @Override
        public Uni<RouteAuthResult> authenticate(GatewayRequest request, RouteMatch route) {
            return Uni.createFrom().item(new RouteAuthResult.Forbidden(reason));
        }
    }
}
