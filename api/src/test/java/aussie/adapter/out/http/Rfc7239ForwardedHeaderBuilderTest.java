package aussie.adapter.out.http;

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

@DisplayName("Rfc7239ForwardedHeaderBuilder")
class Rfc7239ForwardedHeaderBuilderTest {

    private Rfc7239ForwardedHeaderBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new Rfc7239ForwardedHeaderBuilder();
    }

    @Nested
    @DisplayName("Header Building")
    class HeaderBuildingTests {

        @Test
        @DisplayName("Should build Forwarded header with all components")
        void shouldBuildForwardedHeaderWithAllComponents() {
            var request = new TestContainerRequestContext()
                    .withHeader("Host", "api.example.com")
                    .withRequestUri(URI.create("https://localhost:8080/api/test"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api/test"));

            assertTrue(headers.containsKey("Forwarded"));
            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("proto="));
            assertTrue(forwarded.contains("host="));
        }

        @Test
        @DisplayName("Should include 'for' parameter with client IP from X-Forwarded-For")
        void shouldIncludeForParameterFromXForwardedFor() {
            var request = new TestContainerRequestContext().withHeader("X-Forwarded-For", "192.168.1.100");

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("for=192.168.1.100"));
        }

        @Test
        @DisplayName("Should include 'proto' parameter from X-Forwarded-Proto")
        void shouldIncludeProtoParameterFromXForwardedProto() {
            var request = new TestContainerRequestContext().withHeader("X-Forwarded-Proto", "https");

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("proto=https"));
        }

        @Test
        @DisplayName("Should include 'host' parameter from Host header")
        void shouldIncludeHostParameter() {
            var request = new TestContainerRequestContext().withHeader("Host", "api.example.com");

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("host="));
        }

        @Test
        @DisplayName("Should fall back to request URI scheme for proto")
        void shouldFallBackToRequestUriScheme() {
            var request =
                    new TestContainerRequestContext().withRequestUri(URI.create("https://localhost:8443/api/test"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("proto=https"));
        }

        @Test
        @DisplayName("Should extract for from existing Forwarded header")
        void shouldExtractFromExistingForwardedHeader() {
            var request = new TestContainerRequestContext().withHeader("Forwarded", "for=192.168.1.50;proto=https");

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
            var request = new TestContainerRequestContext().withHeader("Host", "api.example.com:8443");

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("host=\"api.example.com:8443\""));
        }

        @Test
        @DisplayName("Should quote IPv6 addresses")
        void shouldQuoteIpv6Addresses() {
            var request = new TestContainerRequestContext().withHeader("X-Forwarded-For", "[::1]");

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
            var request = new TestContainerRequestContext()
                    .withHeader("Forwarded", "for=192.168.1.1;proto=https")
                    .withRequestUri(URI.create("http://localhost:8080/api"));

            var headers = builder.buildHeaders(request, URI.create("http://backend:9090/api"));

            var forwarded = headers.get("Forwarded");
            assertTrue(forwarded.contains("for=192.168.1.1"));
            assertTrue(forwarded.contains(", ")); // Should have comma separator for new entry
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
