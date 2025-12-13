package aussie.system.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import aussie.adapter.out.telemetry.SecurityEventDispatcher;
import aussie.config.RateLimitingConfig;
import aussie.core.model.EffectiveRateLimit;
import aussie.core.model.EndpointConfig;
import aussie.core.model.EndpointVisibility;
import aussie.core.model.RateLimitDecision;
import aussie.core.model.RateLimitKey;
import aussie.core.model.RouteMatch;
import aussie.core.model.ServiceRegistration;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.RateLimiter;
import aussie.core.service.RateLimitResolver;

@DisplayName("RateLimitFilter")
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private RateLimiter rateLimiter;
    private Instance<RateLimitingConfig> configInstance;
    private RateLimitingConfig config;
    private Metrics metrics;
    private SecurityEventDispatcher securityEventDispatcher;
    private RateLimitResolver rateLimitResolver;
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
        requestContext = mock(ContainerRequestContext.class);

        when(configInstance.get()).thenReturn(config);
        when(config.enabled()).thenReturn(true);
        when(config.includeHeaders()).thenReturn(true);

        // Default platform limits (used when no RouteMatch is present)
        var defaultLimit = new EffectiveRateLimit(100, 60, 100);
        when(rateLimitResolver.resolvePlatformDefaults()).thenReturn(defaultLimit);
        when(rateLimitResolver.resolveHttpLimit(any(RouteMatch.class))).thenReturn(defaultLimit);

        filter = new RateLimitFilter(rateLimiter, configInstance, metrics, securityEventDispatcher, rateLimitResolver);
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

    private void setupRequestContext(String serviceId, String clientIp, RouteMatch routeMatch) {
        when(requestContext.getProperty(RequestContextFilter.SERVICE_ID_PROPERTY))
                .thenReturn(serviceId);
        when(requestContext.getProperty(RequestContextFilter.ROUTE_MATCH_PROPERTY))
                .thenReturn(routeMatch);
        when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn(clientIp);
        when(requestContext.getHeaderString("Authorization")).thenReturn(null);
        when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn(null);
        when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);
        when(requestContext.getCookies()).thenReturn(new HashMap<>());
    }

    @Nested
    @DisplayName("When rate limiting is disabled")
    class DisabledTests {

        @Test
        @DisplayName("should skip rate limiting when disabled")
        void shouldSkipRateLimitingWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            setupRequestContext("service-1", "192.168.1.1");

            filter.filter(requestContext);

            verify(rateLimiter, never()).checkAndConsume(any(), any());
        }
    }

    @Nested
    @DisplayName("Rate limit resolution")
    class RateLimitResolutionTests {

        @Test
        @DisplayName("should use resolveHttpLimit when RouteMatch is present")
        void shouldUseResolveHttpLimitWhenRouteMatchPresent() {
            var routeMatch = createRouteMatch("service-1", "/api/users");
            setupRequestContext("service-1", "192.168.1.1", routeMatch);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filter(requestContext);

            verify(rateLimitResolver).resolveHttpLimit(routeMatch);
            verify(rateLimitResolver, never()).resolvePlatformDefaults();
        }

        @Test
        @DisplayName("should use resolvePlatformDefaults when no RouteMatch")
        void shouldUsePlatformDefaultsWhenNoRouteMatch() {
            setupRequestContext("service-1", "192.168.1.1", null);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filter(requestContext);

            verify(rateLimitResolver).resolvePlatformDefaults();
            verify(rateLimitResolver, never()).resolveHttpLimit(any());
        }

        @Test
        @DisplayName("should include endpoint path in rate limit key when RouteMatch present")
        void shouldIncludeEndpointPathInKeyWhenRouteMatchPresent() {
            var routeMatch = createRouteMatch("service-1", "/api/users");
            setupRequestContext("service-1", "192.168.1.1", routeMatch);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filter(requestContext);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals(Optional.of("/api/users"), keyCaptor.getValue().endpointId());
        }

        @Test
        @DisplayName("should have empty endpoint path in key when no RouteMatch")
        void shouldHaveEmptyEndpointPathWhenNoRouteMatch() {
            setupRequestContext("service-1", "192.168.1.1", null);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filter(requestContext);

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
        void shouldUseSessionCookieWhenAvailable() {
            when(requestContext.getProperty(RequestContextFilter.SERVICE_ID_PROPERTY))
                    .thenReturn("service-1");
            when(requestContext.getProperty(RequestContextFilter.ROUTE_MATCH_PROPERTY))
                    .thenReturn(null);

            Map<String, Cookie> cookies = new HashMap<>();
            cookies.put("aussie_session", new Cookie("aussie_session", "session-abc123"));
            when(requestContext.getCookies()).thenReturn(cookies);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filter(requestContext);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertTrue(keyCaptor.getValue().clientId().startsWith("session:"));
        }

        @Test
        @DisplayName("should use authorization header hash when no session")
        void shouldUseAuthorizationHeaderHashWhenNoSession() {
            when(requestContext.getProperty(RequestContextFilter.SERVICE_ID_PROPERTY))
                    .thenReturn("service-1");
            when(requestContext.getProperty(RequestContextFilter.ROUTE_MATCH_PROPERTY))
                    .thenReturn(null);
            when(requestContext.getCookies()).thenReturn(new HashMap<>());
            when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);
            when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer token123");
            when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn(null);
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn(null);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filter(requestContext);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertTrue(keyCaptor.getValue().clientId().startsWith("bearer:"));
        }

        @Test
        @DisplayName("should use API key ID when no session or auth header")
        void shouldUseApiKeyIdWhenNoSessionOrAuthHeader() {
            when(requestContext.getProperty(RequestContextFilter.SERVICE_ID_PROPERTY))
                    .thenReturn("service-1");
            when(requestContext.getProperty(RequestContextFilter.ROUTE_MATCH_PROPERTY))
                    .thenReturn(null);
            when(requestContext.getCookies()).thenReturn(new HashMap<>());
            when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);
            when(requestContext.getHeaderString("Authorization")).thenReturn(null);
            when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn("key-456");
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn(null);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filter(requestContext);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("apikey:key-456", keyCaptor.getValue().clientId());
        }

        @Test
        @DisplayName("should use IP address as fallback")
        void shouldUseIpAddressAsFallback() {
            setupRequestContext("service-1", "10.0.0.1");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filter(requestContext);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("ip:10.0.0.1", keyCaptor.getValue().clientId());
        }

        @Test
        @DisplayName("should extract first IP from X-Forwarded-For")
        void shouldExtractFirstIpFromXForwardedFor() {
            when(requestContext.getProperty(RequestContextFilter.SERVICE_ID_PROPERTY))
                    .thenReturn("service-1");
            when(requestContext.getProperty(RequestContextFilter.ROUTE_MATCH_PROPERTY))
                    .thenReturn(null);
            when(requestContext.getCookies()).thenReturn(new HashMap<>());
            when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);
            when(requestContext.getHeaderString("Authorization")).thenReturn(null);
            when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn(null);
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("10.0.0.1, 192.168.1.1, 172.16.0.1");

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filter(requestContext);

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
        @DisplayName("should use serviceId from request property")
        void shouldUseServiceIdFromRequestProperty() {
            when(requestContext.getProperty(RequestContextFilter.SERVICE_ID_PROPERTY))
                    .thenReturn("my-service");
            when(requestContext.getProperty(RequestContextFilter.ROUTE_MATCH_PROPERTY))
                    .thenReturn(null);
            when(requestContext.getCookies()).thenReturn(new HashMap<>());
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("10.0.0.1");
            when(requestContext.getHeaderString("Authorization")).thenReturn(null);
            when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn(null);
            when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filter(requestContext);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("my-service", keyCaptor.getValue().serviceId());
        }

        @Test
        @DisplayName("should use 'unknown' when serviceId is null")
        void shouldUseUnknownWhenServiceIdIsNull() {
            when(requestContext.getProperty(RequestContextFilter.SERVICE_ID_PROPERTY))
                    .thenReturn(null);
            when(requestContext.getProperty(RequestContextFilter.ROUTE_MATCH_PROPERTY))
                    .thenReturn(null);
            when(requestContext.getCookies()).thenReturn(new HashMap<>());
            when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("10.0.0.1");
            when(requestContext.getHeaderString("Authorization")).thenReturn(null);
            when(requestContext.getHeaderString("X-API-Key-ID")).thenReturn(null);
            when(requestContext.getHeaderString("X-Session-ID")).thenReturn(null);

            var decision = RateLimitDecision.allow();
            when(rateLimiter.checkAndConsume(any(), any()))
                    .thenReturn(Uni.createFrom().item(decision));

            filter.filter(requestContext);

            ArgumentCaptor<RateLimitKey> keyCaptor = ArgumentCaptor.forClass(RateLimitKey.class);
            verify(rateLimiter).checkAndConsume(keyCaptor.capture(), any());

            assertEquals("unknown", keyCaptor.getValue().serviceId());
        }
    }
}
