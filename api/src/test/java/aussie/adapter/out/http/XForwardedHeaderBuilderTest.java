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

import aussie.core.model.gateway.GatewayRequest;

@DisplayName("XForwardedHeaderBuilder")
class XForwardedHeaderBuilderTest {

    private XForwardedHeaderBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new XForwardedHeaderBuilder();
    }

    private GatewayRequest createRequest(Map<String, String> headers, URI requestUri, String clientIp) {
        return createRequest(headers, requestUri, clientIp, null);
    }

    private GatewayRequest createRequest(
            Map<String, String> headers, URI requestUri, String clientIp, String externalScheme) {
        Map<String, List<String>> headerMap = new HashMap<>();
        headers.forEach((k, v) -> headerMap.put(k, List.of(v)));
        return new GatewayRequest("GET", "/api/test", headerMap, requestUri, null, clientIp, externalScheme);
    }

    private GatewayRequest createRequest(Map<String, String> headers) {
        return createRequest(headers, null, null);
    }

    private GatewayRequest createRequest(URI requestUri) {
        return createRequest(Map.of(), requestUri, null);
    }

    private GatewayRequest createRequestWithClientIp(String clientIp) {
        return createRequest(Map.of(), null, clientIp);
    }

    private GatewayRequest createEmptyRequest() {
        return new GatewayRequest("GET", "/api/test", Map.of(), null, null, null);
    }

    @Nested
    @DisplayName("X-Forwarded-For Header")
    class XForwardedForTests {

        @Test
        @DisplayName("Should create X-Forwarded-For from client IP")
        void shouldCreateXForwardedForFromClientIp() {
            var request = createRequestWithClientIp("192.168.1.100");

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertTrue(headers.containsKey("X-Forwarded-For"));
            assertEquals("192.168.1.100", headers.get("X-Forwarded-For"));
        }

        @Test
        @DisplayName("Should replace an inbound X-Forwarded-For chain")
        void shouldReplaceExistingChain() {
            var request = createRequest(Map.of("X-Forwarded-For", "203.0.113.50, 192.168.1.1"), null, "10.0.0.1");

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertEquals("10.0.0.1", headers.get("X-Forwarded-For"));
        }

        @Test
        @DisplayName("Should not trust an inbound chain without a canonical client IP")
        void shouldIgnoreExistingChainWithoutCanonicalClientIp() {
            var request = createRequest(Map.of("X-Forwarded-For", "  203.0.113.50  , 192.168.1.1"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertTrue(!headers.containsKey("X-Forwarded-For"));
        }
    }

    @Nested
    @DisplayName("X-Forwarded-Host Header")
    class XForwardedHostTests {

        @Test
        @DisplayName("Should omit an unvalidated Host header")
        void shouldOmitUnvalidatedHost() {
            var request = createRequest(Map.of("Host", "invalid host"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertTrue(!headers.containsKey("X-Forwarded-Host"));
        }

        @Test
        @DisplayName("Should preserve port in X-Forwarded-Host")
        void shouldPreservePortInXForwardedHost() {
            var request =
                    new GatewayRequest("GET", "/api/test", Map.of(), null, null, null, null, "api.example.com", 8443);

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertEquals("api.example.com:8443", headers.get("X-Forwarded-Host"));
        }

        @Test
        @DisplayName("Should prefer the canonical external authority over the socket Host")
        void shouldUseCanonicalExternalAuthority() {
            final var request = new GatewayRequest(
                    "GET",
                    "/api/test",
                    Map.of("Host", List.of("internal-gateway:8080")),
                    null,
                    null,
                    "198.51.100.5",
                    "https",
                    "api.example.com",
                    9443);

            final var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertEquals("api.example.com:9443", headers.get("X-Forwarded-Host"));
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
        @DisplayName("Should ignore inbound X-Forwarded-Proto")
        void shouldIgnoreInboundXForwardedProto() {
            var request = createRequest(Map.of("X-Forwarded-Proto", "https"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertEquals("http", headers.get("X-Forwarded-Proto"));
        }

        @Test
        @DisplayName("Should fall back to request URI scheme")
        void shouldFallBackToRequestUriScheme() {
            var request = createRequest(URI.create("https://localhost:8443/api/test"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertEquals("https", headers.get("X-Forwarded-Proto"));
        }

        @Test
        @DisplayName("Should preserve a canonical external scheme across TLS termination")
        void shouldUseCanonicalExternalScheme() {
            var request = createRequest(
                    Map.of("X-Forwarded-Proto", "https"),
                    URI.create("http://gateway:8080/api/test"),
                    "198.51.100.5",
                    "https");

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
            var request = new GatewayRequest(
                    "GET",
                    "/api/test",
                    Map.of(),
                    URI.create("http://gateway:8080/api/test"),
                    null,
                    "192.168.1.100",
                    "https",
                    "api.example.com",
                    null);

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertTrue(headers.containsKey("X-Forwarded-For"));
            assertTrue(headers.containsKey("X-Forwarded-Host"));
            assertTrue(headers.containsKey("X-Forwarded-Proto"));
        }
    }
}
