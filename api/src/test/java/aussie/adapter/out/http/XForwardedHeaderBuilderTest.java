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

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;

@DisplayName("XForwardedHeaderBuilder")
class XForwardedHeaderBuilderTest {

    private XForwardedHeaderBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new XForwardedHeaderBuilder();
    }

    @Nested
    @DisplayName("X-Forwarded-For Header")
    class XForwardedForTests {

        @Test
        @DisplayName("Should create X-Forwarded-For from request URI host")
        void shouldCreateXForwardedForFromRequestUri() {
            var request = new TestContainerRequestContext()
                .withRequestUri(URI.create("http://192.168.1.100:8080/api/test"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertTrue(headers.containsKey("X-Forwarded-For"));
            assertEquals("192.168.1.100", headers.get("X-Forwarded-For"));
        }

        @Test
        @DisplayName("Should append to existing X-Forwarded-For chain")
        void shouldAppendToExistingChain() {
            var request = new TestContainerRequestContext()
                .withHeader("X-Forwarded-For", "203.0.113.50, 192.168.1.1")
                .withRequestUri(URI.create("http://10.0.0.1:8080/api/test"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var xff = headers.get("X-Forwarded-For");
            assertTrue(xff.startsWith("203.0.113.50"));
            assertTrue(xff.contains(", "));
        }

        @Test
        @DisplayName("Should extract first IP from existing chain for new entry")
        void shouldExtractFirstIpFromChain() {
            var request = new TestContainerRequestContext()
                .withHeader("X-Forwarded-For", "  203.0.113.50  , 192.168.1.1");

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
            var request = new TestContainerRequestContext()
                .withHeader("Host", "api.example.com");

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertTrue(headers.containsKey("X-Forwarded-Host"));
            assertEquals("api.example.com", headers.get("X-Forwarded-Host"));
        }

        @Test
        @DisplayName("Should preserve port in X-Forwarded-Host")
        void shouldPreservePortInXForwardedHost() {
            var request = new TestContainerRequestContext()
                .withHeader("Host", "api.example.com:8443");

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertEquals("api.example.com:8443", headers.get("X-Forwarded-Host"));
        }

        @Test
        @DisplayName("Should not set X-Forwarded-Host when Host header is missing")
        void shouldNotSetWhenHostMissing() {
            var request = new TestContainerRequestContext();

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
            var request = new TestContainerRequestContext()
                .withHeader("X-Forwarded-Proto", "https");

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertEquals("https", headers.get("X-Forwarded-Proto"));
        }

        @Test
        @DisplayName("Should fall back to request URI scheme")
        void shouldFallBackToRequestUriScheme() {
            var request = new TestContainerRequestContext()
                .withRequestUri(URI.create("https://localhost:8443/api/test"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertEquals("https", headers.get("X-Forwarded-Proto"));
        }

        @Test
        @DisplayName("Should default to http when no info available")
        void shouldDefaultToHttp() {
            var request = new TestContainerRequestContext();

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
            var request = new TestContainerRequestContext()
                .withHeader("Host", "api.example.com")
                .withHeader("X-Forwarded-Proto", "https")
                .withRequestUri(URI.create("http://192.168.1.100:8080/api/test"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            assertTrue(headers.containsKey("X-Forwarded-For"));
            assertTrue(headers.containsKey("X-Forwarded-Host"));
            assertTrue(headers.containsKey("X-Forwarded-Proto"));
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

        // Minimal implementation
        @Override public Object getProperty(String name) { throw new UnsupportedOperationException(); }
        @Override public java.util.Collection<String> getPropertyNames() { throw new UnsupportedOperationException(); }
        @Override public void setProperty(String name, Object object) { throw new UnsupportedOperationException(); }
        @Override public void removeProperty(String name) { throw new UnsupportedOperationException(); }
        @Override public jakarta.ws.rs.core.Request getRequest() { throw new UnsupportedOperationException(); }
        @Override public String getMethod() { throw new UnsupportedOperationException(); }
        @Override public void setMethod(String method) { throw new UnsupportedOperationException(); }
        @Override public MultivaluedMap<String, String> getHeaders() {
            MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
            headers.forEach(map::putSingle);
            return map;
        }
        @Override public java.util.Date getDate() { throw new UnsupportedOperationException(); }
        @Override public java.util.Locale getLanguage() { throw new UnsupportedOperationException(); }
        @Override public int getLength() { throw new UnsupportedOperationException(); }
        @Override public jakarta.ws.rs.core.MediaType getMediaType() { throw new UnsupportedOperationException(); }
        @Override public List<jakarta.ws.rs.core.MediaType> getAcceptableMediaTypes() { throw new UnsupportedOperationException(); }
        @Override public List<java.util.Locale> getAcceptableLanguages() { throw new UnsupportedOperationException(); }
        @Override public Map<String, jakarta.ws.rs.core.Cookie> getCookies() { throw new UnsupportedOperationException(); }
        @Override public boolean hasEntity() { throw new UnsupportedOperationException(); }
        @Override public java.io.InputStream getEntityStream() { throw new UnsupportedOperationException(); }
        @Override public void setEntityStream(java.io.InputStream input) { throw new UnsupportedOperationException(); }
        @Override public jakarta.ws.rs.core.SecurityContext getSecurityContext() { throw new UnsupportedOperationException(); }
        @Override public void setSecurityContext(jakarta.ws.rs.core.SecurityContext context) { throw new UnsupportedOperationException(); }
        @Override public void abortWith(jakarta.ws.rs.core.Response response) { throw new UnsupportedOperationException(); }
        @Override public void setRequestUri(URI requestUri) { throw new UnsupportedOperationException(); }
        @Override public void setRequestUri(URI baseUri, URI requestUri) { throw new UnsupportedOperationException(); }
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

        @Override public String getPath() { throw new UnsupportedOperationException(); }
        @Override public String getPath(boolean decode) { throw new UnsupportedOperationException(); }
        @Override public List<jakarta.ws.rs.core.PathSegment> getPathSegments() { throw new UnsupportedOperationException(); }
        @Override public List<jakarta.ws.rs.core.PathSegment> getPathSegments(boolean decode) { throw new UnsupportedOperationException(); }
        @Override public jakarta.ws.rs.core.UriBuilder getRequestUriBuilder() { throw new UnsupportedOperationException(); }
        @Override public URI getAbsolutePath() { throw new UnsupportedOperationException(); }
        @Override public jakarta.ws.rs.core.UriBuilder getAbsolutePathBuilder() { throw new UnsupportedOperationException(); }
        @Override public URI getBaseUri() { throw new UnsupportedOperationException(); }
        @Override public jakarta.ws.rs.core.UriBuilder getBaseUriBuilder() { throw new UnsupportedOperationException(); }
        @Override public MultivaluedMap<String, String> getPathParameters() { throw new UnsupportedOperationException(); }
        @Override public MultivaluedMap<String, String> getPathParameters(boolean decode) { throw new UnsupportedOperationException(); }
        @Override public MultivaluedMap<String, String> getQueryParameters() { throw new UnsupportedOperationException(); }
        @Override public MultivaluedMap<String, String> getQueryParameters(boolean decode) { throw new UnsupportedOperationException(); }
        @Override public List<String> getMatchedURIs() { throw new UnsupportedOperationException(); }
        @Override public List<String> getMatchedURIs(boolean decode) { throw new UnsupportedOperationException(); }
        @Override public List<Object> getMatchedResources() { throw new UnsupportedOperationException(); }
        @Override public URI resolve(URI uri) { throw new UnsupportedOperationException(); }
        @Override public URI relativize(URI uri) { throw new UnsupportedOperationException(); }
    }
}
