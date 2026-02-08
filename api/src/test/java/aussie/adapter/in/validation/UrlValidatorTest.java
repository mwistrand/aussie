package aussie.adapter.in.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;

import io.quarkiverse.resteasy.problem.HttpProblem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("UrlValidator")
class UrlValidatorTest {

    @Nested
    @DisplayName("validateServiceUrl - valid URLs")
    class ValidUrlTests {

        @Test
        @DisplayName("should accept http URL with domain")
        void shouldAcceptHttpUrl() {
            final var result = UrlValidator.validateServiceUrl("http://api.example.com", "baseUrl");

            assertEquals(URI.create("http://api.example.com"), result);
        }

        @Test
        @DisplayName("should accept https URL with domain")
        void shouldAcceptHttpsUrl() {
            final var result = UrlValidator.validateServiceUrl("https://api.example.com", "baseUrl");

            assertEquals(URI.create("https://api.example.com"), result);
        }

        @Test
        @DisplayName("should accept URL with port")
        void shouldAcceptUrlWithPort() {
            final var result = UrlValidator.validateServiceUrl("https://internal-api:8080/v1", "baseUrl");

            assertEquals(URI.create("https://internal-api:8080/v1"), result);
        }

        @Test
        @DisplayName("should accept URL with path")
        void shouldAcceptUrlWithPath() {
            final var result = UrlValidator.validateServiceUrl("https://api.example.com/v2/service", "baseUrl");

            assertEquals(URI.create("https://api.example.com/v2/service"), result);
        }

        @Test
        @DisplayName("should accept private network IPs for internal routing")
        void shouldAcceptPrivateNetworkIps() {
            assertEquals(
                    URI.create("http://10.0.0.1:8080"),
                    UrlValidator.validateServiceUrl("http://10.0.0.1:8080", "baseUrl"));
            assertEquals(
                    URI.create("http://172.16.0.1:8080"),
                    UrlValidator.validateServiceUrl("http://172.16.0.1:8080", "baseUrl"));
            assertEquals(
                    URI.create("http://192.168.1.1:8080"),
                    UrlValidator.validateServiceUrl("http://192.168.1.1:8080", "baseUrl"));
        }
    }

    @Nested
    @DisplayName("validateServiceUrl - invalid schemes")
    class InvalidSchemeTests {

        @Test
        @DisplayName("should reject file:// scheme")
        void shouldRejectFileScheme() {
            final var ex = assertThrows(
                    HttpProblem.class, () -> UrlValidator.validateServiceUrl("file:///etc/passwd", "baseUrl"));

            assertEquals(400, ex.getStatusCode());
            assertEquals("baseUrl must use http or https scheme", ex.getDetail());
        }

        @Test
        @DisplayName("should reject ftp:// scheme")
        void shouldRejectFtpScheme() {
            final var ex = assertThrows(
                    HttpProblem.class, () -> UrlValidator.validateServiceUrl("ftp://data.example.com", "baseUrl"));

            assertEquals(400, ex.getStatusCode());
            assertEquals("baseUrl must use http or https scheme", ex.getDetail());
        }

        @Test
        @DisplayName("should reject javascript: scheme")
        void shouldRejectJavascriptScheme() {
            final var ex = assertThrows(
                    HttpProblem.class, () -> UrlValidator.validateServiceUrl("javascript:alert(1)", "baseUrl"));

            assertEquals(400, ex.getStatusCode());
            assertEquals("baseUrl must use http or https scheme", ex.getDetail());
        }

        @Test
        @DisplayName("should reject URL without scheme")
        void shouldRejectUrlWithoutScheme() {
            final var ex = assertThrows(
                    HttpProblem.class, () -> UrlValidator.validateServiceUrl("api.example.com", "baseUrl"));

            assertEquals(400, ex.getStatusCode());
            assertEquals("baseUrl must use http or https scheme", ex.getDetail());
        }
    }

    @Nested
    @DisplayName("validateServiceUrl - blocked hosts")
    class BlockedHostTests {

        @Test
        @DisplayName("should reject AWS metadata endpoint")
        void shouldRejectAwsMetadataEndpoint() {
            final var ex = assertThrows(
                    HttpProblem.class,
                    () -> UrlValidator.validateServiceUrl("http://169.254.169.254/metadata", "baseUrl"));

            assertEquals(400, ex.getStatusCode());
            assertEquals("baseUrl must not point to a loopback, link-local, or metadata address", ex.getDetail());
        }

        @Test
        @DisplayName("should reject loopback IP")
        void shouldRejectLoopbackIp() {
            final var ex = assertThrows(
                    HttpProblem.class, () -> UrlValidator.validateServiceUrl("http://127.0.0.1", "baseUrl"));

            assertEquals(400, ex.getStatusCode());
            assertEquals("baseUrl must not point to a loopback, link-local, or metadata address", ex.getDetail());
        }

        @Test
        @DisplayName("should reject localhost hostname")
        void shouldRejectLocalhostHostname() {
            final var ex = assertThrows(
                    HttpProblem.class, () -> UrlValidator.validateServiceUrl("http://localhost:8080", "baseUrl"));

            assertEquals(400, ex.getStatusCode());
            assertEquals("baseUrl must not point to a loopback, link-local, or metadata address", ex.getDetail());
        }

        @Test
        @DisplayName("should reject IPv6 loopback")
        void shouldRejectIpv6Loopback() {
            final var ex =
                    assertThrows(HttpProblem.class, () -> UrlValidator.validateServiceUrl("http://[::1]", "baseUrl"));

            assertEquals(400, ex.getStatusCode());
            assertEquals("baseUrl must not point to a loopback, link-local, or metadata address", ex.getDetail());
        }

        @Test
        @DisplayName("should reject 0.0.0.0 wildcard")
        void shouldRejectZeroWildcard() {
            final var ex =
                    assertThrows(HttpProblem.class, () -> UrlValidator.validateServiceUrl("http://0.0.0.0", "baseUrl"));

            assertEquals(400, ex.getStatusCode());
            assertEquals("baseUrl must not point to a loopback, link-local, or metadata address", ex.getDetail());
        }

        @Test
        @DisplayName("should reject :: wildcard")
        void shouldRejectIpv6Wildcard() {
            final var ex =
                    assertThrows(HttpProblem.class, () -> UrlValidator.validateServiceUrl("http://[::]", "baseUrl"));

            assertEquals(400, ex.getStatusCode());
            assertEquals("baseUrl must not point to a loopback, link-local, or metadata address", ex.getDetail());
        }

        @Test
        @DisplayName("should reject link-local addresses")
        void shouldRejectLinkLocalAddresses() {
            final var ex = assertThrows(
                    HttpProblem.class, () -> UrlValidator.validateServiceUrl("http://169.254.1.1", "baseUrl"));

            assertEquals(400, ex.getStatusCode());
            assertEquals("baseUrl must not point to a loopback, link-local, or metadata address", ex.getDetail());
        }
    }

    @Nested
    @DisplayName("validateServiceUrl - malformed URLs")
    class MalformedUrlTests {

        @Test
        @DisplayName("should reject malformed URL")
        void shouldRejectMalformedUrl() {
            final var ex = assertThrows(
                    HttpProblem.class, () -> UrlValidator.validateServiceUrl("http://[invalid", "baseUrl"));

            assertEquals(400, ex.getStatusCode());
            assertEquals("baseUrl is not a valid URL", ex.getDetail());
        }

        @Test
        @DisplayName("should reject URL with blank host")
        void shouldRejectBlankHost() {
            final var ex = assertThrows(HttpProblem.class, () -> UrlValidator.validateServiceUrl("http://", "baseUrl"));

            assertEquals(400, ex.getStatusCode());
            assertEquals("baseUrl is not a valid URL", ex.getDetail());
        }

        @Test
        @DisplayName("should include parameter name in error message")
        void shouldIncludeParamNameInError() {
            final var ex = assertThrows(
                    HttpProblem.class, () -> UrlValidator.validateServiceUrl("ftp://example.com", "upstreamUrl"));

            assertEquals(400, ex.getStatusCode());
            assertEquals("upstreamUrl must use http or https scheme", ex.getDetail());
        }
    }
}
