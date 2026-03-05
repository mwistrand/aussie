package aussie.core.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.gateway.GatewayRequest;

@DisplayName("ClientIpExtractor")
class ClientIpExtractorTest {

    private GatewayRequest createRequest(Map<String, List<String>> headers, String clientIp) {
        return new GatewayRequest("GET", "/test", headers, URI.create("http://localhost/test"), null, clientIp);
    }

    @Nested
    @DisplayName("Forwarded header")
    class ForwardedHeader {

        @Test
        @DisplayName("should extract IP from Forwarded header with for parameter")
        void forwardedWithFor() {
            var headers = Map.of("Forwarded", List.of("for=192.168.1.1"));
            var request = createRequest(headers, "10.0.0.1");

            var result = ClientIpExtractor.extract(request);

            assertEquals("192.168.1.1", result);
        }

        @Test
        @DisplayName("should strip quotes from Forwarded header value")
        void forwardedWithQuotedValue() {
            var headers = Map.of("Forwarded", List.of("for=\"10.0.0.1\""));
            var request = createRequest(headers, "172.16.0.1");

            var result = ClientIpExtractor.extract(request);

            assertEquals("10.0.0.1", result);
        }

        @Test
        @DisplayName("should use last entry when multiple Forwarded entries exist")
        void multipleForwardedEntries() {
            var headers = Map.of("Forwarded", List.of("for=10.0.0.1, for=192.168.1.100"));
            var request = createRequest(headers, "172.16.0.1");

            var result = ClientIpExtractor.extract(request);

            assertEquals("192.168.1.100", result);
        }

        @Test
        @DisplayName("should fall through to X-Forwarded-For when Forwarded lacks for parameter")
        void forwardedWithoutFor() {
            var headers = Map.of(
                    "Forwarded", List.of("proto=https;host=example.com"),
                    "X-Forwarded-For", List.of("203.0.113.50"));
            var request = createRequest(headers, "10.0.0.1");

            var result = ClientIpExtractor.extract(request);

            assertEquals("203.0.113.50", result);
        }
    }

    @Nested
    @DisplayName("X-Forwarded-For header")
    class XForwardedForHeader {

        @Test
        @DisplayName("should extract single IP from X-Forwarded-For")
        void singleIp() {
            var headers = Map.of("X-Forwarded-For", List.of("203.0.113.50"));
            var request = createRequest(headers, "10.0.0.1");

            var result = ClientIpExtractor.extract(request);

            assertEquals("203.0.113.50", result);
        }

        @Test
        @DisplayName("should use first IP from X-Forwarded-For with multiple IPs")
        void multipleIps() {
            var headers = Map.of("X-Forwarded-For", List.of("203.0.113.50, 70.41.3.18, 150.172.238.178"));
            var request = createRequest(headers, "10.0.0.1");

            var result = ClientIpExtractor.extract(request);

            assertEquals("203.0.113.50", result);
        }
    }

    @Nested
    @DisplayName("Fallback to clientIp")
    class FallbackToClientIp {

        @Test
        @DisplayName("should return clientIp when no forwarding headers present")
        void noForwardingHeaders() {
            var request = createRequest(Map.of(), "192.168.0.100");

            var result = ClientIpExtractor.extract(request);

            assertEquals("192.168.0.100", result);
        }

        @Test
        @DisplayName("should return null when clientIp is null and no headers present")
        void allNull() {
            var request = createRequest(Map.of(), null);

            var result = ClientIpExtractor.extract(request);

            assertNull(result);
        }
    }
}
