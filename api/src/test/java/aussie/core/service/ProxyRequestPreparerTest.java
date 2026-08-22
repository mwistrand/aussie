package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointType;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.model.timeout.EndpointTimeoutConfig;
import aussie.core.model.timeout.ServiceTimeoutConfig;
import aussie.core.port.out.ForwardedHeaderBuilder;
import aussie.core.service.gateway.ProxyRequestPreparer;

@DisplayName("ProxyRequestPreparer")
class ProxyRequestPreparerTest {

    private ProxyRequestPreparer preparer;
    private TestForwardedHeaderBuilder headerBuilder;

    @BeforeEach
    void setUp() {
        headerBuilder = new TestForwardedHeaderBuilder();
        preparer = new ProxyRequestPreparer(() -> headerBuilder);
    }

    private GatewayRequest createRequest(Map<String, List<String>> headers) {
        return new GatewayRequest(
                "GET", "/api/test", headers, URI.create("http://client:8080/api/test"), null, "192.168.1.100");
    }

    private RouteMatch createRoute(String targetUrl) {
        var service =
                ServiceRegistration.builder("test-service").baseUrl(targetUrl).build();
        var endpoint = new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
        return new RouteMatch(service, endpoint, "/api/test", Map.of());
    }

    @Nested
    @DisplayName("prepare()")
    class PrepareTests {

        @Test
        @DisplayName("Should create PreparedProxyRequest with correct method and body")
        void shouldCreatePreparedRequest() {
            var headers = Map.of("Accept", List.of("application/json"));
            var body = "test body".getBytes();
            var request = new GatewayRequest("POST", "/api/test", headers, null, body, "192.168.1.100");
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertEquals("POST", prepared.method());
            assertEquals("http://backend:9090/api/test", prepared.targetUri().toString());
            assertEquals("test body", new String(prepared.body()));
        }

        @Test
        @DisplayName("Should set target URI from route")
        void shouldSetTargetUri() {
            var request = createRequest(Map.of());
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertEquals(URI.create("http://backend:9090/api/test"), prepared.targetUri());
        }
    }

    @Nested
    @DisplayName("Header Filtering")
    class HeaderFilteringTests {

        @Test
        @DisplayName("Should filter hop-by-hop headers")
        void shouldFilterHopByHopHeaders() {
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("Connection", List.of("keep-alive"));
            headers.put("Keep-Alive", List.of("timeout=5"));
            headers.put("Transfer-Encoding", List.of("chunked"));
            headers.put("Upgrade", List.of("websocket"));
            headers.put("Accept", List.of("application/json"));

            var request = createRequest(headers);
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertFalse(prepared.headers().containsKey("Connection"));
            assertFalse(prepared.headers().containsKey("Keep-Alive"));
            assertFalse(prepared.headers().containsKey("Transfer-Encoding"));
            assertFalse(prepared.headers().containsKey("Upgrade"));
            assertTrue(prepared.headers().containsKey("Accept"));
        }

        @Test
        @DisplayName("Should filter Host header (replaced with target)")
        void shouldFilterHostHeader() {
            var headers = Map.of("Host", List.of("original-host.com"));
            var request = createRequest(headers);
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertEquals(List.of("backend:9090"), prepared.headers().get("Host"));
        }

        @Test
        @DisplayName("Should filter Content-Length header")
        void shouldFilterContentLengthHeader() {
            var headers = Map.of("Content-Length", List.of("123"), "Content-Type", List.of("application/json"));
            var request = createRequest(headers);
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertFalse(prepared.headers().containsKey("Content-Length"));
            assertTrue(prepared.headers().containsKey("Content-Type"));
        }

        @Test
        @DisplayName("Should preserve non-hop-by-hop headers")
        void shouldPreserveNonHopByHopHeaders() {
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("Accept", List.of("application/json"));
            headers.put("Authorization", List.of("Bearer token"));
            headers.put("X-Custom-Header", List.of("custom-value"));

            var request = createRequest(headers);
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertEquals(List.of("application/json"), prepared.headers().get("Accept"));
            assertEquals(List.of("Bearer token"), prepared.headers().get("Authorization"));
            assertEquals(List.of("custom-value"), prepared.headers().get("X-Custom-Header"));
        }

        @Test
        @DisplayName("Should handle case-insensitive header names")
        void shouldHandleCaseInsensitiveHeaders() {
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("connection", List.of("keep-alive"));
            headers.put("KEEP-ALIVE", List.of("timeout=5"));
            headers.put("Transfer-ENCODING", List.of("chunked"));

            var request = createRequest(headers);
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertFalse(prepared.headers().containsKey("connection"));
            assertFalse(prepared.headers().containsKey("KEEP-ALIVE"));
            assertFalse(prepared.headers().containsKey("Transfer-ENCODING"));
        }

        @Test
        @DisplayName("Should replace every caller-supplied forwarding header")
        void shouldReplaceInboundForwardingHeaders() {
            final var headers = new HashMap<String, List<String>>();
            headers.put("Forwarded", List.of("for=198.51.100.66"));
            headers.put("X-Forwarded-For", List.of("198.51.100.66"));
            headers.put("X-Forwarded-Host", List.of("attacker.example"));
            headers.put("X-Forwarded-Proto", List.of("https"));
            headers.put("X-Real-IP", List.of("198.51.100.66"));
            headerBuilder.setHeaders(Map.of("Forwarded", "for=192.168.1.100;proto=http;host=client:8080"));

            final var prepared = preparer.prepare(createRequest(headers), createRoute("http://backend:9090"));

            assertEquals(
                    List.of("for=192.168.1.100;proto=http;host=client:8080"),
                    prepared.headers().get("Forwarded"));
            assertFalse(prepared.headers().containsKey("X-Forwarded-For"));
            assertFalse(prepared.headers().containsKey("X-Forwarded-Host"));
            assertFalse(prepared.headers().containsKey("X-Forwarded-Proto"));
            assertFalse(prepared.headers().containsKey("X-Real-IP"));
        }

        @Test
        @DisplayName("Should filter headers dynamically declared in Connection header")
        void shouldFilterDynamicHopByHopHeaders() {
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("Connection", List.of("X-Custom-Header, X-Internal-Token"));
            headers.put("X-Custom-Header", List.of("some-value"));
            headers.put("X-Internal-Token", List.of("secret"));
            headers.put("Accept", List.of("application/json"));

            var request = createRequest(headers);
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertFalse(prepared.headers().containsKey("Connection"));
            assertFalse(prepared.headers().containsKey("X-Custom-Header"));
            assertFalse(prepared.headers().containsKey("X-Internal-Token"));
            assertTrue(prepared.headers().containsKey("Accept"));
        }

        @Test
        @DisplayName("Should not strip close or keep-alive as header names from Connection")
        void shouldNotStripConnectionDirectives() {
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("Connection", List.of("close, X-Strip-Me"));
            headers.put("X-Strip-Me", List.of("gone"));
            headers.put("close", List.of("should-survive"));
            headers.put("Accept", List.of("text/html"));

            var request = createRequest(headers);
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertFalse(prepared.headers().containsKey("X-Strip-Me"));
            assertTrue(prepared.headers().containsKey("close"));
            assertTrue(prepared.headers().containsKey("Accept"));
        }

        @Test
        @DisplayName("Should handle case-insensitive dynamic hop-by-hop headers")
        void shouldHandleCaseInsensitiveDynamicHopByHop() {
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("Connection", List.of("X-Auth-Token"));
            headers.put("X-AUTH-TOKEN", List.of("secret"));
            headers.put("Accept", List.of("application/json"));

            var request = createRequest(headers);
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertFalse(prepared.headers().containsKey("X-AUTH-TOKEN"));
            assertTrue(prepared.headers().containsKey("Accept"));
        }

        @Test
        @DisplayName("Should handle multiple Connection header values")
        void shouldHandleMultipleConnectionValues() {
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("Connection", List.of("X-Header-One", "X-Header-Two"));
            headers.put("X-Header-One", List.of("val1"));
            headers.put("X-Header-Two", List.of("val2"));
            headers.put("Accept", List.of("*/*"));

            var request = createRequest(headers);
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertFalse(prepared.headers().containsKey("X-Header-One"));
            assertFalse(prepared.headers().containsKey("X-Header-Two"));
            assertTrue(prepared.headers().containsKey("Accept"));
        }

        @Test
        @DisplayName("Should handle empty Connection header value")
        void shouldHandleEmptyConnectionHeaderValue() {
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("Connection", List.of(""));
            headers.put("Accept", List.of("application/json"));

            var request = createRequest(headers);
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertTrue(prepared.headers().containsKey("Accept"));
        }

        @Test
        @DisplayName("Should preserve all headers when Connection contains only directives")
        void shouldPreserveHeadersWhenConnectionContainsOnlyDirectives() {
            Map<String, List<String>> headers = new HashMap<>();
            headers.put("Connection", List.of("close, keep-alive"));
            headers.put("Accept", List.of("application/json"));
            headers.put("X-Custom", List.of("value"));

            var request = createRequest(headers);
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertTrue(prepared.headers().containsKey("Accept"));
            assertTrue(prepared.headers().containsKey("X-Custom"));
        }

        @Test
        @DisplayName("Should resolve Connection header in any case (canonical, lower, upper)")
        void shouldResolveConnectionHeaderInAnyCase() {
            for (final var key : List.of("Connection", "connection", "CONNECTION")) {
                final Map<String, List<String>> headers = new HashMap<>();
                headers.put(key, List.of("X-Strip-Me"));
                headers.put("X-Strip-Me", List.of("gone"));
                headers.put("Accept", List.of("application/json"));

                final var prepared = preparer.prepare(createRequest(headers), createRoute("http://backend:9090"));

                assertFalse(prepared.headers().containsKey("X-Strip-Me"), "case=" + key);
                assertTrue(prepared.headers().containsKey("Accept"), "case=" + key);
            }
        }

        @Test
        @DisplayName("Forwarded value lists are passed through by reference, not copied")
        void shouldShareValueListReference() {
            final var acceptValues = List.of("application/json");
            final Map<String, List<String>> headers = new HashMap<>();
            headers.put("Accept", acceptValues);

            final var prepared = preparer.prepare(createRequest(headers), createRoute("http://backend:9090"));

            assertSame(acceptValues, prepared.headers().get("Accept"));
        }
    }

    @Nested
    @DisplayName("Host Header")
    class HostHeaderTests {

        @Test
        @DisplayName("Should set Host with port for non-standard ports")
        void shouldSetHostWithNonStandardPort() {
            var request = createRequest(Map.of());
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertEquals(List.of("backend:9090"), prepared.headers().get("Host"));
        }

        @Test
        @DisplayName("Should set Host without port for HTTP on port 80")
        void shouldOmitPort80ForHttp() {
            var request = createRequest(Map.of());
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://backend:80")
                    .build();
            var endpoint = new EndpointConfig("/api", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api", Map.of());

            var prepared = preparer.prepare(request, route);

            assertEquals(List.of("backend"), prepared.headers().get("Host"));
        }

        @Test
        @DisplayName("Should set Host without port for HTTPS on port 443")
        void shouldOmitPort443ForHttps() {
            var request = createRequest(Map.of());
            var service = ServiceRegistration.builder("test")
                    .baseUrl("https://backend:443")
                    .build();
            var endpoint = new EndpointConfig("/api", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api", Map.of());

            var prepared = preparer.prepare(request, route);

            assertEquals(List.of("backend"), prepared.headers().get("Host"));
        }

        @Test
        @DisplayName("Should set Host without port when not specified")
        void shouldHandleMissingPort() {
            var request = createRequest(Map.of());
            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://backend")
                    .build();
            var endpoint = new EndpointConfig("/api", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api", Map.of());

            var prepared = preparer.prepare(request, route);

            assertEquals(List.of("backend"), prepared.headers().get("Host"));
        }
    }

    @Nested
    @DisplayName("Forwarding Headers")
    class ForwardingHeadersTests {

        @Test
        @DisplayName("Should add forwarding headers from builder")
        void shouldAddForwardingHeaders() {
            headerBuilder.setHeaders(Map.of(
                    "X-Forwarded-For", "192.168.1.1",
                    "X-Forwarded-Host", "original.com",
                    "X-Forwarded-Proto", "https"));

            var request = createRequest(Map.of());
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertEquals(List.of("192.168.1.1"), prepared.headers().get("X-Forwarded-For"));
            assertEquals(List.of("original.com"), prepared.headers().get("X-Forwarded-Host"));
            assertEquals(List.of("https"), prepared.headers().get("X-Forwarded-Proto"));
        }

        @Test
        @DisplayName("Should add X-Forwarded-Prefix from route prefix")
        void shouldAddXForwardedPrefix() {
            var request = createRequest(Map.of());
            var service = ServiceRegistration.builder("my-service")
                    .baseUrl("http://backend:9090")
                    .routePrefix("/my-service")
                    .build();
            var endpoint = new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api/test", Map.of());

            var prepared = preparer.prepare(request, route);

            assertEquals(List.of("/my-service"), prepared.headers().get("X-Forwarded-Prefix"));
        }

        @Test
        @DisplayName("Should use default route prefix from service ID")
        void shouldUseDefaultRoutePrefixFromServiceId() {
            var request = createRequest(Map.of());
            // When routePrefix is not explicitly set, it defaults to /{serviceId}
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertEquals(List.of("/test-service"), prepared.headers().get("X-Forwarded-Prefix"));
        }
    }

    @Nested
    @DisplayName("filterResponseHeaders()")
    class FilterResponseHeadersTests {

        @Test
        @DisplayName("Should filter hop-by-hop headers from response")
        void shouldFilterHopByHopFromResponse() {
            Map<String, List<String>> responseHeaders = new HashMap<>();
            responseHeaders.put("Content-Type", List.of("application/json"));
            responseHeaders.put("Connection", List.of("keep-alive"));
            responseHeaders.put("Transfer-Encoding", List.of("chunked"));
            responseHeaders.put("X-Custom", List.of("value"));

            var filtered = preparer.filterResponseHeaders(responseHeaders);

            assertTrue(filtered.containsKey("Content-Type"));
            assertTrue(filtered.containsKey("X-Custom"));
            assertFalse(filtered.containsKey("Connection"));
            assertFalse(filtered.containsKey("Transfer-Encoding"));
        }

        @Test
        @DisplayName("Should preserve Content-Length in response")
        void shouldPreserveContentLengthInResponse() {
            Map<String, List<String>> responseHeaders = new HashMap<>();
            responseHeaders.put("Content-Length", List.of("1234"));

            var filtered = preparer.filterResponseHeaders(responseHeaders);

            assertTrue(filtered.containsKey("Content-Length"));
        }

        @Test
        @DisplayName("Should handle case-insensitive filtering")
        void shouldHandleCaseInsensitiveFiltering() {
            Map<String, List<String>> responseHeaders = new HashMap<>();
            responseHeaders.put("TRANSFER-ENCODING", List.of("chunked"));
            responseHeaders.put("connection", List.of("close"));

            var filtered = preparer.filterResponseHeaders(responseHeaders);

            assertFalse(filtered.containsKey("TRANSFER-ENCODING"));
            assertFalse(filtered.containsKey("connection"));
        }

        @Test
        @DisplayName("Should filter dynamically declared hop-by-hop headers from response")
        void shouldFilterDynamicHopByHopFromResponse() {
            Map<String, List<String>> responseHeaders = new HashMap<>();
            responseHeaders.put("Connection", List.of("X-Upstream-Internal"));
            responseHeaders.put("X-Upstream-Internal", List.of("debug-info"));
            responseHeaders.put("Content-Type", List.of("application/json"));

            var filtered = preparer.filterResponseHeaders(responseHeaders);

            assertFalse(filtered.containsKey("Connection"));
            assertFalse(filtered.containsKey("X-Upstream-Internal"));
            assertTrue(filtered.containsKey("Content-Type"));
        }
    }

    @Nested
    @DisplayName("Timeout Resolution")
    class TimeoutResolutionTests {

        @Test
        @DisplayName("Should use endpoint timeout when both endpoint and service timeouts are set")
        void shouldUseEndpointTimeoutOverService() {
            var request = createRequest(Map.of());
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://backend:9090")
                    .timeoutConfig(ServiceTimeoutConfig.of(Duration.ofSeconds(30)))
                    .build();
            var endpoint = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.HTTP,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(EndpointTimeoutConfig.of(Duration.ofSeconds(60))),
                    Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api/test", Map.of());

            var prepared = preparer.prepare(request, route);

            assertEquals(Optional.of(Duration.ofSeconds(60)), prepared.requestTimeout());
        }

        @Test
        @DisplayName("Should fall back to service timeout when endpoint timeout is absent")
        void shouldFallBackToServiceTimeout() {
            var request = createRequest(Map.of());
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://backend:9090")
                    .timeoutConfig(ServiceTimeoutConfig.of(Duration.ofSeconds(45)))
                    .build();
            var endpoint = new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api/test", Map.of());

            var prepared = preparer.prepare(request, route);

            assertEquals(Optional.of(Duration.ofSeconds(45)), prepared.requestTimeout());
        }

        @Test
        @DisplayName("Should return empty when neither endpoint nor service timeout is set")
        void shouldReturnEmptyWhenNoTimeout() {
            var request = createRequest(Map.of());
            var route = createRoute("http://backend:9090");

            var prepared = preparer.prepare(request, route);

            assertTrue(prepared.requestTimeout().isEmpty());
        }

        @Test
        @DisplayName("Should return empty when endpoint has defaults and no service timeout")
        void shouldReturnEmptyWithEndpointDefaults() {
            var request = createRequest(Map.of());
            var service = ServiceRegistration.builder("test-service")
                    .baseUrl("http://backend:9090")
                    .build();
            var endpoint = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.HTTP,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(EndpointTimeoutConfig.defaults()),
                    Optional.empty());
            var route = new RouteMatch(service, endpoint, "/api/test", Map.of());

            var prepared = preparer.prepare(request, route);

            assertTrue(prepared.requestTimeout().isEmpty());
        }
    }

    private static class TestForwardedHeaderBuilder implements ForwardedHeaderBuilder {
        private Map<String, String> headers = Map.of();

        void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        @Override
        public Map<String, String> buildHeaders(GatewayRequest originalRequest, URI targetUri) {
            return headers;
        }
    }
}
