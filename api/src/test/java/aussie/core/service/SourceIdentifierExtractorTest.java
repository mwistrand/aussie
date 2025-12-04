package aussie.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SourceIdentifierExtractor")
class SourceIdentifierExtractorTest {

    private SourceIdentifierExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new SourceIdentifierExtractor();
    }

    @Nested
    @DisplayName("IP Address Extraction")
    class IpAddressExtractionTests {

        @Test
        @DisplayName("Should extract IP from X-Forwarded-For header")
        void shouldExtractFromXForwardedFor() {
            var request = new TestContainerRequestContext().withHeader("X-Forwarded-For", "192.168.1.100");

            var result = extractor.extract(request);
            assertEquals("192.168.1.100", result.ipAddress());
        }

        @Test
        @DisplayName("Should extract first IP from X-Forwarded-For chain")
        void shouldExtractFirstIpFromChain() {
            var request = new TestContainerRequestContext()
                    .withHeader("X-Forwarded-For", "203.0.113.50, 192.168.1.1, 10.0.0.1");

            var result = extractor.extract(request);
            assertEquals("203.0.113.50", result.ipAddress());
        }

        @Test
        @DisplayName("Should trim whitespace from X-Forwarded-For")
        void shouldTrimWhitespace() {
            var request = new TestContainerRequestContext().withHeader("X-Forwarded-For", "  192.168.1.100  ");

            var result = extractor.extract(request);
            assertEquals("192.168.1.100", result.ipAddress());
        }

        @Test
        @DisplayName("Should extract IP from RFC 7239 Forwarded header")
        void shouldExtractFromForwardedHeader() {
            var request = new TestContainerRequestContext()
                    .withHeader("Forwarded", "for=192.168.1.100;proto=https;by=10.0.0.1");

            var result = extractor.extract(request);
            assertEquals("192.168.1.100", result.ipAddress());
        }

        @Test
        @DisplayName("Should extract quoted IP from Forwarded header")
        void shouldExtractQuotedIpFromForwarded() {
            var request = new TestContainerRequestContext().withHeader("Forwarded", "for=\"192.168.1.100\"");

            var result = extractor.extract(request);
            assertEquals("192.168.1.100", result.ipAddress());
        }

        @Test
        @DisplayName("Should prefer X-Forwarded-For over Forwarded")
        void shouldPreferXForwardedFor() {
            var request = new TestContainerRequestContext()
                    .withHeader("X-Forwarded-For", "10.0.0.1")
                    .withHeader("Forwarded", "for=192.168.1.100");

            var result = extractor.extract(request);
            assertEquals("10.0.0.1", result.ipAddress());
        }

        @Test
        @DisplayName("Should fall back to X-Real-IP header")
        void shouldFallBackToXRealIp() {
            var request = new TestContainerRequestContext().withHeader("X-Real-IP", "172.16.0.1");

            var result = extractor.extract(request);
            assertEquals("172.16.0.1", result.ipAddress());
        }

        @Test
        @DisplayName("Should fall back to request URI host")
        void shouldFallBackToRequestUriHost() {
            var request =
                    new TestContainerRequestContext().withRequestUri(URI.create("http://localhost:8080/api/test"));

            var result = extractor.extract(request);
            assertEquals("localhost", result.ipAddress());
        }

        @Test
        @DisplayName("Should return 'unknown' when no IP available")
        void shouldReturnUnknownWhenNoIp() {
            var request = new TestContainerRequestContext();

            var result = extractor.extract(request);
            assertEquals("unknown", result.ipAddress());
        }
    }

    @Nested
    @DisplayName("Host Extraction")
    class HostExtractionTests {

        @Test
        @DisplayName("Should extract host from X-Forwarded-Host header")
        void shouldExtractFromXForwardedHost() {
            var request = new TestContainerRequestContext().withHeader("X-Forwarded-Host", "api.example.com");

            var result = extractor.extract(request);
            assertTrue(result.host().isPresent());
            assertEquals("api.example.com", result.host().get());
        }

        @Test
        @DisplayName("Should extract first host from X-Forwarded-Host chain")
        void shouldExtractFirstHostFromChain() {
            var request = new TestContainerRequestContext()
                    .withHeader("X-Forwarded-Host", "api.example.com, internal.example.com");

            var result = extractor.extract(request);
            assertTrue(result.host().isPresent());
            assertEquals("api.example.com", result.host().get());
        }

        @Test
        @DisplayName("Should extract host from Forwarded header")
        void shouldExtractFromForwardedHeader() {
            var request =
                    new TestContainerRequestContext().withHeader("Forwarded", "for=192.168.1.1;host=api.example.com");

            var result = extractor.extract(request);
            assertTrue(result.host().isPresent());
            assertEquals("api.example.com", result.host().get());
        }

        @Test
        @DisplayName("Should prefer X-Forwarded-Host over Forwarded")
        void shouldPreferXForwardedHost() {
            var request = new TestContainerRequestContext()
                    .withHeader("X-Forwarded-Host", "preferred.example.com")
                    .withHeader("Forwarded", "host=fallback.example.com");

            var result = extractor.extract(request);
            assertEquals("preferred.example.com", result.host().get());
        }

        @Test
        @DisplayName("Should fall back to Host header")
        void shouldFallBackToHostHeader() {
            var request = new TestContainerRequestContext().withHeader("Host", "localhost:8080");

            var result = extractor.extract(request);
            assertTrue(result.host().isPresent());
            assertEquals("localhost", result.host().get());
        }

        @Test
        @DisplayName("Should strip port from Host header")
        void shouldStripPortFromHostHeader() {
            var request = new TestContainerRequestContext().withHeader("Host", "api.example.com:443");

            var result = extractor.extract(request);
            assertEquals("api.example.com", result.host().get());
        }

        @Test
        @DisplayName("Should return empty when no host available")
        void shouldReturnEmptyWhenNoHost() {
            var request = new TestContainerRequestContext();

            var result = extractor.extract(request);
            assertFalse(result.host().isPresent());
        }
    }

    @Nested
    @DisplayName("Forwarded For Chain Extraction")
    class ForwardedForChainTests {

        @Test
        @DisplayName("Should extract full forwarded-for chain")
        void shouldExtractFullChain() {
            var request = new TestContainerRequestContext()
                    .withHeader("X-Forwarded-For", "203.0.113.50, 192.168.1.1, 10.0.0.1");

            var result = extractor.extract(request);
            assertTrue(result.forwardedFor().isPresent());
            assertEquals(
                    "203.0.113.50, 192.168.1.1, 10.0.0.1", result.forwardedFor().get());
        }

        @Test
        @DisplayName("Should return empty when no X-Forwarded-For")
        void shouldReturnEmptyWhenNoXForwardedFor() {
            var request = new TestContainerRequestContext().withHeader("Forwarded", "for=192.168.1.1");

            var result = extractor.extract(request);
            assertFalse(result.forwardedFor().isPresent());
        }
    }

    @Nested
    @DisplayName("RFC 7239 Forwarded Header Parsing")
    class ForwardedHeaderParsingTests {

        @Test
        @DisplayName("Should parse multiple parameters")
        void shouldParseMultipleParameters() {
            var request = new TestContainerRequestContext()
                    .withHeader("Forwarded", "for=192.168.1.1;proto=https;host=example.com;by=10.0.0.1");

            var result = extractor.extract(request);
            assertEquals("192.168.1.1", result.ipAddress());
            assertEquals("example.com", result.host().get());
        }

        @Test
        @DisplayName("Should handle case-insensitive parameter names")
        void shouldHandleCaseInsensitiveParams() {
            var request = new TestContainerRequestContext().withHeader("Forwarded", "FOR=192.168.1.1;HOST=example.com");

            var result = extractor.extract(request);
            assertEquals("192.168.1.1", result.ipAddress());
            assertEquals("example.com", result.host().get());
        }

        @Test
        @DisplayName("Should parse first entry from multiple Forwarded entries")
        void shouldParseFirstEntry() {
            var request = new TestContainerRequestContext().withHeader("Forwarded", "for=192.168.1.1, for=10.0.0.1");

            var result = extractor.extract(request);
            assertEquals("192.168.1.1", result.ipAddress());
        }
    }

    /**
     * Simple test implementation of ContainerRequestContext.
     */
    private static class TestContainerRequestContext implements ContainerRequestContext {
        private final Map<String, String> headers = new HashMap<>();
        private URI requestUri;

        TestContainerRequestContext withHeader(String name, String value) {
            headers.put(name, value);
            return this;
        }

        TestContainerRequestContext withRequestUri(URI uri) {
            this.requestUri = uri;
            return this;
        }

        @Override
        public String getHeaderString(String name) {
            return headers.get(name);
        }

        @Override
        public UriInfo getUriInfo() {
            if (requestUri == null) {
                return null;
            }
            return new TestUriInfo(requestUri);
        }

        // Minimal implementation - other methods throw UnsupportedOperationException
        @Override
        public Object getProperty(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Collection<String> getPropertyNames() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setProperty(String name, Object object) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeProperty(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.Request getRequest() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getMethod() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setMethod(String method) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MultivaluedMap<String, String> getHeaders() {
            MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
            headers.forEach(map::putSingle);
            return map;
        }

        @Override
        public java.util.Date getDate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Locale getLanguage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getLength() {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.MediaType getMediaType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<jakarta.ws.rs.core.MediaType> getAcceptableMediaTypes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<java.util.Locale> getAcceptableLanguages() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, jakarta.ws.rs.core.Cookie> getCookies() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasEntity() {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.io.InputStream getEntityStream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setEntityStream(java.io.InputStream input) {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext getSecurityContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setSecurityContext(jakarta.ws.rs.core.SecurityContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abortWith(jakarta.ws.rs.core.Response response) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setRequestUri(URI requestUri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setRequestUri(URI baseUri, URI requestUri) {
            throw new UnsupportedOperationException();
        }
    }

    private static class TestUriInfo implements UriInfo {
        private final URI requestUri;

        TestUriInfo(URI requestUri) {
            this.requestUri = requestUri;
        }

        @Override
        public URI getRequestUri() {
            return requestUri;
        }

        // Minimal implementation
        @Override
        public String getPath() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getPath(boolean decode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<jakarta.ws.rs.core.PathSegment> getPathSegments() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<jakarta.ws.rs.core.PathSegment> getPathSegments(boolean decode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.UriBuilder getRequestUriBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public URI getAbsolutePath() {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.UriBuilder getAbsolutePathBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public URI getBaseUri() {
            throw new UnsupportedOperationException();
        }

        @Override
        public jakarta.ws.rs.core.UriBuilder getBaseUriBuilder() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MultivaluedMap<String, String> getPathParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MultivaluedMap<String, String> getPathParameters(boolean decode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MultivaluedMap<String, String> getQueryParameters() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MultivaluedMap<String, String> getQueryParameters(boolean decode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> getMatchedURIs() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> getMatchedURIs(boolean decode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Object> getMatchedResources() {
            throw new UnsupportedOperationException();
        }

        @Override
        public URI resolve(URI uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public URI relativize(URI uri) {
            throw new UnsupportedOperationException();
        }
    }
}
