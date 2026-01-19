package aussie.system.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;
import io.vertx.core.MultiMap;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import aussie.adapter.out.telemetry.SecurityEventDispatcher;
import aussie.adapter.out.telemetry.TelemetryHelper;
import aussie.core.config.RateLimitingConfig;
import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.RateLimitDecision;
import aussie.core.model.ratelimit.RateLimitKey;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.RateLimiter;
import aussie.core.service.ratelimit.RateLimitResolver;
import aussie.core.service.routing.ServiceRegistry;

@DisplayName("RateLimitFilter")
class RateLimitFilterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private RateLimitFilter filter;
    private RateLimiter rateLimiter;
    private Instance<RateLimitingConfig> configInstance;
    private RateLimitingConfig config;
    private Metrics metrics;
    private SecurityEventDispatcher securityEventDispatcher;
    private RateLimitResolver rateLimitResolver;
    private ServiceRegistry serviceRegistry;
    private TelemetryHelper telemetryHelper;
    private HttpServerRequest request;
    private HttpServerResponse response;
    private MultiMap responseHeaders;
    private ContainerRequestContext requestContext;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        configInstance = mock(Instance.class);
        config = mock(RateLimitingConfig.class);
        metrics = mock(Metrics.class);
        securityEventDispatcher = mock(SecurityEventDispatcher.class);
        rateLimitResolver = mock(RateLimitResolver.class);
        serviceRegistry = mock(ServiceRegistry.class);
        telemetryHelper = mock(TelemetryHelper.class);
        request = mock(HttpServerRequest.class);
        response = mock(HttpServerResponse.class);
        responseHeaders = MultiMap.caseInsensitiveMultiMap();
        requestContext = mock(ContainerRequestContext.class);

        when(configInstance.get()).thenReturn(config);
        when(config.enabled()).thenReturn(true);
        when(config.includeHeaders()).thenReturn(true);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.response()).thenReturn(response);
        when(response.headers()).thenReturn(responseHeaders);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);

        // Default platform limits (used when no RouteLookupResult is present)
        var defaultLimit = new EffectiveRateLimit(100, 60, 100);
        when(rateLimitResolver.resolvePlatformDefaults()).thenReturn(defaultLimit);
        when(rateLimitResolver.resolveLimit(any(RouteLookupResult.class))).thenReturn(defaultLimit);

        // Default: no route found
        when(serviceRegistry.findRoute(anyString(), anyString())).thenReturn(Optional.empty());

        filter = new RateLimitFilter(
                rateLimiter,
                configInstance,
                metrics,
                securityEventDispatcher,
                rateLimitResolver,
                serviceRegistry,
                telemetryHelper);
    }

    private ServiceRegistration createTestService(String serviceId) {
        return ServiceRegistration.builder(serviceId)
                .displayName(serviceId)
                .baseUrl(URI.create("http://localhost:8081"))
                .endpoints(List.of(new EndpointConfig(
                        "/api/users", Set.of("GET", "POST"), EndpointVisibility.PUBLIC, Optional.empty())))
                .build();
    }

    private RouteMatch createRouteMatch(String serviceId, String endpointPath) {
        var service = createTestService(serviceId);
        var endpoint = new EndpointConfig(endpointPath, Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
        return new RouteMatch(service, endpoint, endpointPath, Map.of());
    }

    private void setupRequest(String path, String clientIp) {
        when(request.path()).thenReturn(path);
        when(request.getHeader("Forwarded")).thenReturn(null);
        when(request.getHeader("X-Forwarded-For")).thenReturn(clientIp);
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getHeader("X-API-Key-ID")).thenReturn(null);
        when(request.getHeader("X-Session-ID")).thenReturn(null);
        when(request.getCookie(anyString())).thenReturn(null);
        when(request.remoteAddress()).thenReturn(null);
    }

    @Nested
    @DisplayName("When rate limiting is disabled")
    class DisabledTests {

        @Test
        @DisplayName("should skip rate limiting when disabled")
        void shouldSkipRateLimitingWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            when(request.path()).thenReturn("/service-1/api/test");

            Response result =
                    filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            assertNull(result);
            verify(rateLimiter, never()).checkAndConsume(any(), any());
        }
    }

    @Nested
    @DisplayName("Rate limit resolution")
    class RateLimitResolutionTests {

        @Test
        @DisplayName("should use resolveLimit when route is found")
        void shouldUseResolveHttpLimitWhenRouteMatchPresent() {
            var service = ServiceRegistration.builder("service-1")
                    .displayName("service-1")
                    .baseUrl(URI.create("http://localhost:8081"))
                    .endpoints(List.of(new EndpointConfig(
                            "/api/users", Set.of("GET", "POST"), EndpointVisibility.PUBLIC, Optional.empty())))
                    .build();
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            setupRequest("/service-1/api/users", "192.168.1.1");
            when(serviceRegistry.findRoute("/service-1/api/users", "GET")).thenReturn(Optional.of(routeMatch));

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            Response result =
                    filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            assertNull(result);
            verify(rateLimitResolver).resolveLimit(any(RouteLookupResult.class));
            verify(rateLimitResolver, never()).resolvePlatformDefaults();
        }

        @Test
        @DisplayName("should use resolvePlatformDefaults when no service found")
        void shouldUsePlatformDefaultsWhenNoServiceFound() {
            setupRequest("/service-1/api/users", "192.168.1.1");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            Response result =
                    filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            assertNull(result);
            verify(rateLimitResolver).resolvePlatformDefaults();
            verify(rateLimitResolver, never()).resolveLimit(any());
        }

        @Test
        @DisplayName("should include endpoint path in rate limit key when route is found")
        void shouldIncludeEndpointPathInKeyWhenRouteMatchPresent() {
            var service = ServiceRegistration.builder("service-1")
                    .displayName("service-1")
                    .baseUrl(URI.create("http://localhost:8081"))
                    .endpoints(List.of(new EndpointConfig(
                            "/api/users", Set.of("GET", "POST"), EndpointVisibility.PUBLIC, Optional.empty())))
                    .build();
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            setupRequest("/service-1/api/users", "192.168.1.1");
            when(serviceRegistry.findRoute("/service-1/api/users", "GET")).thenReturn(Optional.of(routeMatch));

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals(Optional.of("/api/users"), keyCaptor.getValue().endpointId());
        }

        @Test
        @DisplayName("should have empty endpoint path in key when no RouteMatch")
        void shouldHaveEmptyEndpointPathWhenNoRouteMatch() {
            setupRequest("/service-1/api/users", "192.168.1.1");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals(Optional.empty(), keyCaptor.getValue().endpointId());
        }
    }

    @Nested
    @DisplayName("When rate limiting is enabled")
    class EnabledTests {

        @Test
        @DisplayName("should allow request when limit not exceeded")
        void shouldAllowRequestWhenLimitNotExceeded() {
            setupRequest("/service-1/api/test", "192.168.1.1");

            var decision = RateLimitDecision.allow(99, 100, 60, Instant.now().plusSeconds(60), 1, null);
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            Response result =
                    filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            assertNull(result);
            verify(metrics).recordRateLimitCheck(eq("service-1"), eq(true), eq(99L));
        }

        @Test
        @DisplayName("should reject request when limit exceeded")
        void shouldRejectRequestWhenLimitExceeded() {
            setupRequest("/service-1/api/test", "192.168.1.1");

            var decision = RateLimitDecision.rejected(100, 60, Instant.now().plusSeconds(60), 5, 101, null);
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            Response result =
                    filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            assertNotNull(result);
            assertEquals(429, result.getStatus());
            assertNotNull(result.getHeaderString("Retry-After"));
        }

        @Test
        @DisplayName("should record metrics on allowed request")
        void shouldRecordMetricsOnAllowedRequest() {
            setupRequest("/service-1/api/test", "192.168.1.1");

            var decision = RateLimitDecision.allow(99, 100, 60, Instant.now().plusSeconds(60), 1, null);
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            verify(metrics).recordRateLimitCheck(eq("service-1"), eq(true), eq(99L));
        }

        @Test
        @DisplayName("should record metrics on rejected request")
        void shouldRecordMetricsOnRejectedRequest() {
            setupRequest("/service-1/api/test", "192.168.1.1");

            var decision = RateLimitDecision.rejected(100, 60, Instant.now().plusSeconds(60), 5, 101, null);
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            verify(metrics).recordRateLimitCheck(eq("service-1"), eq(false), eq(0L));
            verify(metrics).recordRateLimitExceeded(eq("service-1"), eq("http"));
        }

        @Test
        @DisplayName("should dispatch security event on rate limit exceeded")
        void shouldDispatchSecurityEventOnRateLimitExceeded() {
            setupRequest("/service-1/api/test", "192.168.1.1");

            var decision = RateLimitDecision.rejected(100, 60, Instant.now().plusSeconds(60), 5, 101, null);
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            verify(securityEventDispatcher).dispatch(any());
        }
    }

    @Nested
    @DisplayName("Client ID extraction")
    class ClientIdExtractionTests {

        @Test
        @DisplayName("should use session cookie when available")
        void shouldUseSessionCookieWhenAvailable() {
            setupRequest("/service-1/api/test", null);
            var cookie = mock(Cookie.class);
            when(cookie.getValue()).thenReturn("session-abc123");
            when(request.getCookie("aussie_session")).thenReturn(cookie);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertTrue(keyCaptor.getValue().clientId().startsWith("session:"));
        }

        @Test
        @DisplayName("should use authorization header hash when no session")
        void shouldUseAuthorizationHeaderHashWhenNoSession() {
            setupRequest("/service-1/api/test", null);
            when(request.getHeader("Authorization")).thenReturn("Bearer token123");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertTrue(keyCaptor.getValue().clientId().startsWith("bearer:"));
        }

        @Test
        @DisplayName("should use API key ID when no session or auth header")
        void shouldUseApiKeyIdWhenNoSessionOrAuthHeader() {
            setupRequest("/service-1/api/test", null);
            when(request.getHeader("X-API-Key-ID")).thenReturn("key-456");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("apikey:key-456", keyCaptor.getValue().clientId());
        }

        @Test
        @DisplayName("should use IP address as fallback")
        void shouldUseIpAddressAsFallback() {
            setupRequest("/service-1/api/test", "10.0.0.1");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("ip:10.0.0.1", keyCaptor.getValue().clientId());
        }

        @Test
        @DisplayName("should extract first IP from X-Forwarded-For")
        void shouldExtractFirstIpFromXForwardedFor() {
            setupRequest("/service-1/api/test", null);
            when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1, 192.168.1.1, 172.16.0.1");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("ip:10.0.0.1", keyCaptor.getValue().clientId());
        }

        @Test
        @DisplayName("should prefer RFC 7239 Forwarded header over X-Forwarded-For")
        void shouldPreferRfc7239ForwardedHeader() {
            setupRequest("/service-1/api/test", null);
            when(request.getHeader("Forwarded")).thenReturn("for=203.0.113.195");
            when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("ip:203.0.113.195", keyCaptor.getValue().clientId());
        }

        @Test
        @DisplayName("should parse RFC 7239 Forwarded header with multiple directives")
        void shouldParseForwardedHeaderWithMultipleDirectives() {
            setupRequest("/service-1/api/test", null);
            when(request.getHeader("Forwarded")).thenReturn("for=192.0.2.60;proto=http;by=203.0.113.43");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("ip:192.0.2.60", keyCaptor.getValue().clientId());
        }

        @Test
        @DisplayName("should parse RFC 7239 Forwarded header with IPv6 address")
        void shouldParseForwardedHeaderWithIPv6() {
            setupRequest("/service-1/api/test", null);
            when(request.getHeader("Forwarded")).thenReturn("for=\"[2001:db8:cafe::17]\"");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("ip:2001:db8:cafe::17", keyCaptor.getValue().clientId());
        }

        @Test
        @DisplayName("should parse RFC 7239 Forwarded header with port")
        void shouldParseForwardedHeaderWithPort() {
            setupRequest("/service-1/api/test", null);
            when(request.getHeader("Forwarded")).thenReturn("for=192.0.2.60:8080");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("ip:192.0.2.60", keyCaptor.getValue().clientId());
        }

        @Test
        @DisplayName("should use remote address when no headers present")
        void shouldUseRemoteAddressWhenNoHeadersPresent() {
            setupRequest("/service-1/api/test", null);
            var socketAddress = mock(SocketAddress.class);
            when(socketAddress.host()).thenReturn("127.0.0.1");
            when(request.remoteAddress()).thenReturn(socketAddress);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("ip:127.0.0.1", keyCaptor.getValue().clientId());
        }
    }

    @Nested
    @DisplayName("Service ID extraction")
    class ServiceIdExtractionTests {

        @Test
        @DisplayName("should extract serviceId from path")
        void shouldExtractServiceIdFromPath() {
            setupRequest("/my-service/api/test", "10.0.0.1");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("my-service", keyCaptor.getValue().serviceId());
        }

        @Test
        @DisplayName("should use 'unknown' when path is empty")
        void shouldUseUnknownWhenPathIsEmpty() {
            setupRequest("/", "10.0.0.1");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filterRequest(requestContext, request).await().atMost(TIMEOUT);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("unknown", keyCaptor.getValue().serviceId());
        }
    }
}
