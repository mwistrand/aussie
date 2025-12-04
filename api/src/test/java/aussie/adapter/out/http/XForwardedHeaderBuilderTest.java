package aussie.adapter.out.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.GatewayRequest;

@DisplayName("XForwardedHeaderBuilder")
class XForwardedHeaderBuilderTest {

    private XForwardedHeaderBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new XForwardedHeaderBuilder();
    }

    private GatewayRequest createRequest(Map<String, String> headers, URI requestUri) {
        Map<String, List<String>> headerMap = new HashMap<>();
        headers.forEach((k, v) -> headerMap.put(k, List.of(v)));
        return new GatewayRequest("GET", "/api/test", headerMap, requestUri, null);
    }

    private GatewayRequest createRequest(Map<String, String> headers) {
        return createRequest(headers, null);
    }

    private GatewayRequest createRequest(URI requestUri) {
        return createRequest(Map.of(), requestUri);
    }

    private GatewayRequest createEmptyRequest() {
        return new GatewayRequest("GET", "/api/test", Map.of(), null, null);
    }

    @Nested
    @DisplayName("X-Forwarded-For Header")
    class XForwardedForTests {

        @Test
        @DisplayName("Should create X-Forwarded-For from request URI host")
        void shouldCreateXForwardedForFromRequestUri() {
            var request = createRequest(URI.create("http://192.168.1.100:8080/api/test"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertTrue(headers.containsKey("X-Forwarded-For"));
            assertEquals("192.168.1.100", headers.get("X-Forwarded-For"));
        }

        @Test
        @DisplayName("Should append to existing X-Forwarded-For chain")
        void shouldAppendToExistingChain() {
            var request = createRequest(
                    Map.of("X-Forwarded-For", "203.0.113.50, 192.168.1.1"),
                    URI.create("http://10.0.0.1:8080/api/test"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var xff = headers.get("X-Forwarded-For");
            assertTrue(xff.startsWith("203.0.113.50"));
            assertTrue(xff.contains(", "));
        }

        @Test
        @DisplayName("Should extract first IP from existing chain for new entry")
        void shouldExtractFirstIpFromChain() {
            var request = createRequest(Map.of("X-Forwarded-For", "  203.0.113.50  , 192.168.1.1"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            // First IP should be preserved at start
            assertTrue(headers.get("X-Forwarded-For").contains("203.0.113.50"));
        }
    }

    @Nested
    @DisplayName("X-Forwarded-Host Header")
    class XForwardedHostTests {

        @Test
        @DisplayName("Should set X-Forwarded-Host from Host header")
        void shouldSetXForwardedHostFromHostHeader() {
            var request = createRequest(Map.of("Host", "api.example.com"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertTrue(headers.containsKey("X-Forwarded-Host"));
            assertEquals("api.example.com", headers.get("X-Forwarded-Host"));
        }

        @Test
        @DisplayName("Should preserve port in X-Forwarded-Host")
        void shouldPreservePortInXForwardedHost() {
            var request = createRequest(Map.of("Host", "api.example.com:8443"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertEquals("api.example.com:8443", headers.get("X-Forwarded-Host"));
        }

        @Test
        @DisplayName("Should not set X-Forwarded-Host when Host header is missing")
        void shouldNotSetWhenHostMissing() {
            var request = createEmptyRequest();

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertTrue(!headers.containsKey("X-Forwarded-Host") || headers.get("X-Forwarded-Host") == null);
        }
    }

    @Nested
    @DisplayName("X-Forwarded-Proto Header")
    class XForwardedProtoTests {

        @Test
        @DisplayName("Should set X-Forwarded-Proto from existing header")
        void shouldSetXForwardedProtoFromExistingHeader() {
            var request = createRequest(Map.of("X-Forwarded-Proto", "https"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertEquals("https", headers.get("X-Forwarded-Proto"));
        }

        @Test
        @DisplayName("Should fall back to request URI scheme")
        void shouldFallBackToRequestUriScheme() {
            var request = createRequest(URI.create("https://localhost:8443/api/test"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertEquals("https", headers.get("X-Forwarded-Proto"));
        }

        @Test
        @DisplayName("Should default to http when no info available")
        void shouldDefaultToHttp() {
            var request = createEmptyRequest();

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertEquals("http", headers.get("X-Forwarded-Proto"));
        }
    }

    @Nested
    @DisplayName("All Headers Together")
    class AllHeadersTests {

        @Test
        @DisplayName("Should set all three X-Forwarded headers")
        void shouldSetAllThreeHeaders() {
            Map<String, List<String>> headerMap = new HashMap<>();
            headerMap.put("Host", List.of("api.example.com"));
            headerMap.put("X-Forwarded-Proto", List.of("https"));
            var request = new GatewayRequest(
                    "GET", "/api/test", headerMap, URI.create("http://192.168.1.100:8080/api/test"), null);

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertTrue(headers.containsKey("X-Forwarded-For"));
            assertTrue(headers.containsKey("X-Forwarded-Host"));
            assertTrue(headers.containsKey("X-Forwarded-Proto"));
        }
    }
}
