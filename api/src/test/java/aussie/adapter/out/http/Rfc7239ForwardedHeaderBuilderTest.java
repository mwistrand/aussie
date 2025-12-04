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

import aussie.core.model.GatewayRequest;

@DisplayName("Rfc7239ForwardedHeaderBuilder")
class Rfc7239ForwardedHeaderBuilderTest {

    private Rfc7239ForwardedHeaderBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new Rfc7239ForwardedHeaderBuilder();
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

    @Nested
    @DisplayName("Header Building")
    class HeaderBuildingTests {

        @Test
        @DisplayName("Should build Forwarded header with all components")
        void shouldBuildForwardedHeaderWithAllComponents() {
            var request =
                    createRequest(Map.of("Host", "api.example.com"), URI.create("https://localhost:8080/api/test"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api/test"));

            assertTrue(headers.containsKey("Forwarded"));
            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("proto="));
            assertTrue(forwarded.contains("host="));
        }

        @Test
        @DisplayName("Should include 'for' parameter with client IP from X-Forwarded-For")
        void shouldIncludeForParameterFromXForwardedFor() {
            var request = createRequest(Map.of("X-Forwarded-For", "192.168.1.100"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("for=192.168.1.100"));
        }

        @Test
        @DisplayName("Should include 'proto' parameter from X-Forwarded-Proto")
        void shouldIncludeProtoParameterFromXForwardedProto() {
            var request = createRequest(Map.of("X-Forwarded-Proto", "https"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("proto=https"));
        }

        @Test
        @DisplayName("Should include 'host' parameter from Host header")
        void shouldIncludeHostParameter() {
            var request = createRequest(Map.of("Host", "api.example.com"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("host="));
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
        @DisplayName("Should extract for from existing Forwarded header")
        void shouldExtractFromExistingForwardedHeader() {
            var request = createRequest(Map.of("Forwarded", "for=192.168.1.50;proto=https"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("for=192.168.1.50") || forwarded.contains("for=\"192.168.1.50\""));
        }
    }

    @Nested
    @DisplayName("Quoting Special Characters")
    class QuotingTests {

        @Test
        @DisplayName("Should quote host with port")
        void shouldQuoteHostWithPort() {
            var request = createRequest(Map.of("Host", "api.example.com:8443"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("host=\"api.example.com:8443\""));
        }

        @Test
        @DisplayName("Should quote IPv6 addresses")
        void shouldQuoteIpv6Addresses() {
            var request = createRequest(Map.of("X-Forwarded-For", "[::1]"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("for=\"[::1]\""));
        }
    }

    @Nested
    @DisplayName("Chaining Forwarded Headers")
    class ChainingTests {

        @Test
        @DisplayName("Should append to existing Forwarded header")
        void shouldAppendToExistingForwarded() {
            var request = createRequest(
                    Map.of("Forwarded", "for=192.168.1.1;proto=https"), URI.create("http://localhost:8080/api"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("for=192.168.1.1"));
            assertTrue(forwarded.contains(", ")); // Should have comma separator for new entry
        }
    }
}
