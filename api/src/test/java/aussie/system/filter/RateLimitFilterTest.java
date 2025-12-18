package aussie.system.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import io.smallrye.mutiny.Uni;
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

    private RateLimitFilter filter;
    private RateLimiter rateLimiter;
    private Instance<RateLimitingConfig> configInstance;
    private RateLimitingConfig config;
    private Metrics metrics;
    private SecurityEventDispatcher securityEventDispatcher;
    private RateLimitResolver rateLimitResolver;
    private ServiceRegistry serviceRegistry;
    private TelemetryHelper telemetryHelper;
    private ContainerRequestContext requestContext;
    private UriInfo uriInfo;

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
        requestContext = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);

        when(configInstance.get()).thenReturn(config);
        when(config.enabled()).thenReturn(true);
        when(config.includeHeaders()).thenReturn(true);
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(requestContext.getMethod()).thenReturn("GET");

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

    private void setupRequestContext(String serviceId, String clientIp) {
        setupRequestContext(serviceId, clientIp, null);
    }

    private void setupRequestContext(String serviceId, String clientIp, ServiceRegistration service) {
        // Set up path so ServicePath.parse() extracts the service ID
        when(uriInfo.getPath()).thenReturn("/" + serviceId + "/api/test");
        when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn(clientIp);
        when(requestContext.getHeaderString("Authorization")).thenReturn(null);
        when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn(null);
        when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);
        when(requestContext.getCookies()).thenReturn(new HashMap<>());

        // Set up service registry mock
        if (service != null) {
            when(serviceRegistry.getService(serviceId))
                    .thenReturn(Uni.createFrom().item(Optional.of(service)));
        } else {
            when(serviceRegistry.getService(serviceId))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
        }
    }

    @Nested
    @DisplayName("When rate limiting is disabled")
    class DisabledTests {

        @Test
        @DisplayName("should skip rate limiting when disabled")
        void shouldSkipRateLimitingWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            when(uriInfo.getPath()).thenReturn("/service-1/api/test");

            filter.filter(requestContext);

            verify(rateLimiter, never()).checkAndConsume(any(), any());
            verify(serviceRegistry, never()).getService(anyString());
        }
    }

    @Nested
    @DisplayName("Rate limit resolution")
    class RateLimitResolutionTests {

        @Test
        @DisplayName("should use resolveLimit when route is found")
        void shouldUseResolveHttpLimitWhenRouteMatchPresent() throws InterruptedException {
            var service = ServiceRegistration.builder("service-1")
                    .displayName("service-1")
                    .baseUrl(URI.create("http://localhost:8081"))
                    .endpoints(List.of(new EndpointConfig(
                            "/api/users", Set.of("GET", "POST"), EndpointVisibility.PUBLIC, Optional.empty())))
                    .build();
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            when(uriInfo.getPath()).thenReturn("/service-1/api/users");
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("192.168.1.1");
            when(requestContext.getHeaderString("Authorization")).thenReturn(null);
            when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn(null);
            when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);
            when(requestContext.getCookies()).thenReturn(new HashMap<>());
            when(serviceRegistry.findRoute("/service-1/api/users", "GET")).thenReturn(Optional.of(routeMatch));

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .setProperty(eq("aussie.ratelimit.decision"), any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "setProperty was not called in time");

            verify(rateLimitResolver).resolveLimit(any(RouteLookupResult.class));
            verify(rateLimitResolver, never()).resolvePlatformDefaults();
        }

        @Test
        @DisplayName("should use resolvePlatformDefaults when no service found")
        void shouldUsePlatformDefaultsWhenNoServiceFound() throws InterruptedException {
            when(uriInfo.getPath()).thenReturn("/service-1/api/users");
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("192.168.1.1");
            when(requestContext.getHeaderString("Authorization")).thenReturn(null);
            when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn(null);
            when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);
            when(requestContext.getCookies()).thenReturn(new HashMap<>());
            when(serviceRegistry.getService("service-1"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .setProperty(eq("aussie.ratelimit.decision"), any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "setProperty was not called in time");

            verify(rateLimitResolver).resolvePlatformDefaults();
            verify(rateLimitResolver, never()).resolveLimit(any());
        }

        @Test
        @DisplayName("should include endpoint path in rate limit key when route is found")
        void shouldIncludeEndpointPathInKeyWhenRouteMatchPresent() throws InterruptedException {
            var service = ServiceRegistration.builder("service-1")
                    .displayName("service-1")
                    .baseUrl(URI.create("http://localhost:8081"))
                    .endpoints(List.of(new EndpointConfig(
                            "/api/users", Set.of("GET", "POST"), EndpointVisibility.PUBLIC, Optional.empty())))
                    .build();
            var endpoint = new EndpointConfig("/api/users", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var routeMatch = new RouteMatch(service, endpoint, "/api/users", Map.of());

            when(uriInfo.getPath()).thenReturn("/service-1/api/users");
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("192.168.1.1");
            when(requestContext.getHeaderString("Authorization")).thenReturn(null);
            when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn(null);
            when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);
            when(requestContext.getCookies()).thenReturn(new HashMap<>());
            when(serviceRegistry.findRoute("/service-1/api/users", "GET")).thenReturn(Optional.of(routeMatch));

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .setProperty(eq("aussie.ratelimit.decision"), any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "setProperty was not called in time");

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals(Optional.of("/api/users"), keyCaptor.getValue().endpointId());
        }

        @Test
        @DisplayName("should have empty endpoint path in key when no RouteMatch")
        void shouldHaveEmptyEndpointPathWhenNoRouteMatch() throws InterruptedException {
            when(uriInfo.getPath()).thenReturn("/service-1/api/users");
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("192.168.1.1");
            when(requestContext.getHeaderString("Authorization")).thenReturn(null);
            when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn(null);
            when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);
            when(requestContext.getCookies()).thenReturn(new HashMap<>());
            when(serviceRegistry.getService("service-1"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .setProperty(eq("aussie.ratelimit.decision"), any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "setProperty was not called in time");

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
        void shouldAllowRequestWhenLimitNotExceeded() throws InterruptedException {
            setupRequestContext("service-1", "192.168.1.1");

            var decision = RateLimitDecision.allow(99, 100, 60, Instant.now().plusSeconds(60), 1, null);
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .setProperty(eq("aussie.ratelimit.decision"), any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "setProperty was not called in time");

            verify(requestContext).setProperty(eq("aussie.ratelimit.decision"), any(RateLimitDecision.class));
            verify(requestContext, never()).abortWith(any());
        }

        @Test
        @DisplayName("should reject request when limit exceeded")
        void shouldRejectRequestWhenLimitExceeded() throws InterruptedException {
            setupRequestContext("service-1", "192.168.1.1");

            var decision = RateLimitDecision.rejected(100, 60, Instant.now().plusSeconds(60), 5, 101, null);
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .abortWith(any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "abortWith was not called in time");

            ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
            verify(requestContext).abortWith(responseCaptor.capture());

            var response = responseCaptor.getValue();
            assertEquals(429, response.getStatus());
            assertNotNull(response.getHeaderString("Retry-After"));
        }

        @Test
        @DisplayName("should record metrics on allowed request")
        void shouldRecordMetricsOnAllowedRequest() throws InterruptedException {
            setupRequestContext("service-1", "192.168.1.1");

            var decision = RateLimitDecision.allow(99, 100, 60, Instant.now().plusSeconds(60), 1, null);
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .abortWith(any());

            filter.filter(requestContext);

            latch.await(1, TimeUnit.SECONDS);

            verify(metrics).recordRateLimitCheck(eq("service-1"), eq(true), eq(99L));
        }

        @Test
        @DisplayName("should record metrics on rejected request")
        void shouldRecordMetricsOnRejectedRequest() throws InterruptedException {
            setupRequestContext("service-1", "192.168.1.1");

            var decision = RateLimitDecision.rejected(100, 60, Instant.now().plusSeconds(60), 5, 101, null);
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .abortWith(any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "abortWith was not called in time");

            verify(metrics).recordRateLimitCheck(eq("service-1"), eq(false), eq(0L));
            verify(metrics).recordRateLimitExceeded(eq("service-1"), eq("http"));
        }

        @Test
        @DisplayName("should dispatch security event on rate limit exceeded")
        void shouldDispatchSecurityEventOnRateLimitExceeded() throws InterruptedException {
            setupRequestContext("service-1", "192.168.1.1");

            var decision = RateLimitDecision.rejected(100, 60, Instant.now().plusSeconds(60), 5, 101, null);
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .abortWith(any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "abortWith was not called in time");

            verify(securityEventDispatcher).dispatch(any());
        }
    }

    @Nested
    @DisplayName("Client ID extraction")
    class ClientIdExtractionTests {

        @Test
        @DisplayName("should use session cookie when available")
        void shouldUseSessionCookieWhenAvailable() throws InterruptedException {
            when(uriInfo.getPath()).thenReturn("/service-1/api/test");
            when(serviceRegistry.getService("service-1"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));

            Map<String, Cookie> cookies = new HashMap<>();
            cookies.put("aussie_session", new Cookie("aussie_session", "session-abc123"));
            when(requestContext.getCookies()).thenReturn(cookies);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .setProperty(eq("aussie.ratelimit.decision"), any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "setProperty was not called in time");

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertTrue(keyCaptor.getValue().clientId().startsWith("session:"));
        }

        @Test
        @DisplayName("should use authorization header hash when no session")
        void shouldUseAuthorizationHeaderHashWhenNoSession() throws InterruptedException {
            when(uriInfo.getPath()).thenReturn("/service-1/api/test");
            when(serviceRegistry.getService("service-1"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            when(requestContext.getCookies()).thenReturn(new HashMap<>());
            when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);
            when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer token123");
            when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn(null);
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn(null);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .setProperty(eq("aussie.ratelimit.decision"), any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "setProperty was not called in time");

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertTrue(keyCaptor.getValue().clientId().startsWith("bearer:"));
        }

        @Test
        @DisplayName("should use API key ID when no session or auth header")
        void shouldUseApiKeyIdWhenNoSessionOrAuthHeader() throws InterruptedException {
            when(uriInfo.getPath()).thenReturn("/service-1/api/test");
            when(serviceRegistry.getService("service-1"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            when(requestContext.getCookies()).thenReturn(new HashMap<>());
            when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);
            when(requestContext.getHeaderString("Authorization")).thenReturn(null);
            when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn("key-456");
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn(null);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .setProperty(eq("aussie.ratelimit.decision"), any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "setProperty was not called in time");

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("apikey:key-456", keyCaptor.getValue().clientId());
        }

        @Test
        @DisplayName("should use IP address as fallback")
        void shouldUseIpAddressAsFallback() throws InterruptedException {
            setupRequestContext("service-1", "10.0.0.1");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .setProperty(eq("aussie.ratelimit.decision"), any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "setProperty was not called in time");

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("ip:10.0.0.1", keyCaptor.getValue().clientId());
        }

        @Test
        @DisplayName("should extract first IP from X-Forwarded-For")
        void shouldExtractFirstIpFromXForwardedFor() throws InterruptedException {
            when(uriInfo.getPath()).thenReturn("/service-1/api/test");
            when(serviceRegistry.getService("service-1"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            when(requestContext.getCookies()).thenReturn(new HashMap<>());
            when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);
            when(requestContext.getHeaderString("Authorization")).thenReturn(null);
            when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn(null);
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("10.0.0.1, 192.168.1.1, 172.16.0.1");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .setProperty(eq("aussie.ratelimit.decision"), any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "setProperty was not called in time");

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("ip:10.0.0.1", keyCaptor.getValue().clientId());
        }
    }

    @Nested
    @DisplayName("Response filter (headers)")
    class ResponseFilterTests {

        @Test
        @DisplayName("should add rate limit headers when enabled")
        void shouldAddRateLimitHeadersWhenEnabled() {
            var responseContext = mock(ContainerResponseContext.class);
            var headers = new MultivaluedHashMap<String, Object>();
            when(responseContext.getHeaders()).thenReturn(headers);

            var decision = RateLimitDecision.allow(95, 100, 60, Instant.now().plusSeconds(30), 5, null);
            when(requestContext.getProperty("aussie.ratelimit.decision")).thenReturn(decision);

            filter.filter(requestContext, responseContext);

            assertTrue(headers.containsKey("X-RateLimit-Limit"));
            assertTrue(headers.containsKey("X-RateLimit-Remaining"));
            assertTrue(headers.containsKey("X-RateLimit-Reset"));
            assertEquals(100L, headers.getFirst("X-RateLimit-Limit"));
            assertEquals(95L, headers.getFirst("X-RateLimit-Remaining"));
        }

        @Test
        @DisplayName("should not add headers when config disabled")
        void shouldNotAddHeadersWhenConfigDisabled() {
            when(config.includeHeaders()).thenReturn(false);

            var responseContext = mock(ContainerResponseContext.class);
            var headers = new MultivaluedHashMap<String, Object>();
            when(responseContext.getHeaders()).thenReturn(headers);

            filter.filter(requestContext, responseContext);

            assertFalse(headers.containsKey("X-RateLimit-Limit"));
        }

        @Test
        @DisplayName("should not add headers for rejected requests")
        void shouldNotAddHeadersForRejectedRequests() {
            var responseContext = mock(ContainerResponseContext.class);
            var headers = new MultivaluedHashMap<String, Object>();
            when(responseContext.getHeaders()).thenReturn(headers);

            var decision = RateLimitDecision.rejected(100, 60, Instant.now().plusSeconds(30), 5, 101, null);
            when(requestContext.getProperty("aussie.ratelimit.decision")).thenReturn(decision);

            filter.filter(requestContext, responseContext);

            // Headers should not be added for rejected requests (they're already in the 429
            // response)
            assertFalse(headers.containsKey("X-RateLimit-Limit"));
        }

        @Test
        @DisplayName("should handle missing decision gracefully")
        void shouldHandleMissingDecisionGracefully() {
            var responseContext = mock(ContainerResponseContext.class);
            var headers = new MultivaluedHashMap<String, Object>();
            when(responseContext.getHeaders()).thenReturn(headers);

            when(requestContext.getProperty("aussie.ratelimit.decision")).thenReturn(null);

            // Should not throw
            filter.filter(requestContext, responseContext);

            assertFalse(headers.containsKey("X-RateLimit-Limit"));
        }
    }

    @Nested
    @DisplayName("Service ID extraction")
    class ServiceIdExtractionTests {

        @Test
        @DisplayName("should extract serviceId from path")
        void shouldExtractServiceIdFromPath() throws InterruptedException {
            when(uriInfo.getPath()).thenReturn("/my-service/api/test");
            when(serviceRegistry.getService("my-service"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            when(requestContext.getCookies()).thenReturn(new HashMap<>());
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("10.0.0.1");
            when(requestContext.getHeaderString("Authorization")).thenReturn(null);
            when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn(null);
            when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .setProperty(eq("aussie.ratelimit.decision"), any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "setProperty was not called in time");

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("my-service", keyCaptor.getValue().serviceId());
        }

        @Test
        @DisplayName("should use 'unknown' when path is empty")
        void shouldUseUnknownWhenPathIsEmpty() throws InterruptedException {
            when(uriInfo.getPath()).thenReturn("/");
            when(serviceRegistry.getService("unknown"))
                    .thenReturn(Uni.createFrom().item(Optional.empty()));
            when(requestContext.getCookies()).thenReturn(new HashMap<>());
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("10.0.0.1");
            when(requestContext.getHeaderString("Authorization")).thenReturn(null);
            when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn(null);
            when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            CountDownLatch latch = new CountDownLatch(1);
            doAnswer(invocation -> {
                        latch.countDown();
                        return null;
                    })
                    .when(requestContext)
                    .setProperty(eq("aussie.ratelimit.decision"), any());

            filter.filter(requestContext);

            boolean completed = latch.await(1, TimeUnit.SECONDS);
            assertTrue(completed, "setProperty was not called in time");

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("unknown", keyCaptor.getValue().serviceId());
        }
    }
}
