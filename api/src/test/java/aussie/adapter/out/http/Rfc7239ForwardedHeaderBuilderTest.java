package aussie.adapter.out.http;

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

@DisplayName("Rfc7239ForwardedHeaderBuilder")
class Rfc7239ForwardedHeaderBuilderTest {

    private Rfc7239ForwardedHeaderBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new Rfc7239ForwardedHeaderBuilder();
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

    @Nested
    @DisplayName("Header Building")
    class HeaderBuildingTests {

        @Test
        @DisplayName("Should build Forwarded header with all components")
        void shouldBuildForwardedHeaderWithAllComponents() {
            var request = new GatewayRequest(
                    "GET",
                    "/api/test",
                    Map.of(),
                    URI.create("https://gateway:8080/api/test"),
                    null,
                    "192.168.1.100",
                    "https",
                    "api.example.com",
                    null);

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api/test"));

            assertTrue(headers.containsKey("Forwarded"));
            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("for=192.168.1.100"));
            assertTrue(forwarded.contains("proto="));
            assertTrue(forwarded.contains("host="));
        }

        @Test
        @DisplayName("Should ignore caller-supplied X-Forwarded-For")
        void shouldIgnoreCallerSuppliedXForwardedFor() {
            var request = createRequest(Map.of("X-Forwarded-For", "192.168.1.100"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(!forwarded.contains("for="));
        }

        @Test
        @DisplayName("Should ignore caller-supplied X-Forwarded-Proto")
        void shouldIgnoreCallerSuppliedXForwardedProto() {
            var request = createRequest(Map.of("X-Forwarded-Proto", "https"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("proto=http"));
        }

        @Test
        @DisplayName("Should omit an unvalidated Host header")
        void shouldOmitUnvalidatedHost() {
            var request = createRequest(Map.of("Host", "invalid host"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(!forwarded.contains("host="));
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

            final var forwarded = builder.buildHeaders(request, URI.create("http://backend:9090/api"))
                    .get("Forwarded");

            assertTrue(forwarded.contains("host=\"api.example.com:9443\""));
        }

        @Test
        @DisplayName("Should fall back to request URI scheme for proto")
        void shouldFallBackToRequestUriScheme() {
            var request = createRequest(URI.create("https://localhost:8443/api/test"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("proto=https"));
        }

        @Test
        @DisplayName("Should preserve a canonical external scheme across TLS termination")
        void shouldUseCanonicalExternalScheme() {
            var request = createRequest(
                    Map.of("Forwarded", "for=198.51.100.5;proto=https"),
                    URI.create("http://gateway:8080/api/test"),
                    "198.51.100.5",
                    "https");

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertTrue(headers.get("Forwarded").contains("proto=https"));
        }

        @Test
        @DisplayName("Should ignore caller-supplied Forwarded header")
        void shouldIgnoreExistingForwardedHeader() {
            var request = createRequest(Map.of("Forwarded", "for=192.168.1.50;proto=https"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(!forwarded.contains("for="));
        }
    }

    @Nested
    @DisplayName("Quoting Special Characters")
    class QuotingTests {

        @Test
        @DisplayName("Should quote host with port")
        void shouldQuoteHostWithPort() {
            var request =
                    new GatewayRequest("GET", "/api/test", Map.of(), null, null, null, null, "api.example.com", 8443);

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("host=\"api.example.com:8443\""));
        }

        @Test
        @DisplayName("Should quote IPv6 addresses")
        void shouldQuoteIpv6Addresses() {
            var request = createRequest(Map.of(), null, "::1");

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("for=\"[::1]\""));
        }
    }

    @Nested
    @DisplayName("Chaining Forwarded Headers")
    class ChainingTests {

        @Test
        @DisplayName("Should replace an existing Forwarded header")
        void shouldReplaceExistingForwarded() {
            var request = createRequest(
                    Map.of("Forwarded", "for=192.168.1.1;proto=https"),
                    URI.create("http://localhost:8080/api"),
                    "10.0.0.1");

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("for=10.0.0.1"));
            assertTrue(!forwarded.contains("192.168.1.1"));
            assertTrue(!forwarded.contains(", "));
        }
    }
}
