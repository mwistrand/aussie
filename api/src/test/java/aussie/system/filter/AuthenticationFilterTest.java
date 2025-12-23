package aussie.system.filter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.spi.AuthenticationProvider;

/**
 * Test for the deprecated {@link AuthenticationFilter}.
 *
 * <p>Note: The AuthenticationFilter is deprecated and disabled by default.
 * Quarkus Security handles authentication via ApiKeyAuthenticationMechanism.
 * These tests verify that the filter correctly skips processing when
 * legacy mode is disabled (the default).
 */
@DisplayName("AuthenticationFilter (deprecated)")
@SuppressWarnings("unchecked")
class AuthenticationFilterTest {

    private ContainerRequestContext requestContext;
    private UriInfo uriInfo;
    private Instance<AuthenticationProvider> providers;

    @BeforeEach
    void setUp() {
        requestContext = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);
        providers = mock(Instance.class);
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(requestContext.getHeaders()).thenReturn(new MultivaluedHashMap<>());
    }

    @Nested
    @DisplayName("when legacy filter is disabled (default)")
    class LegacyFilterDisabledTests {

        @Test
        @DisplayName("should skip processing for admin paths")
        void shouldSkipProcessingForAdminPaths() throws IOException {
            when(uriInfo.getPath()).thenReturn("admin/services");

            var filter = new AuthenticationFilter(providers);
            filter.filter(requestContext);

            // Filter should not abort or set any properties when legacy mode is disabled
            verify(requestContext, never()).abortWith(any());
            verify(requestContext, never()).setProperty(any(), any());
        }

        @Test
        @DisplayName("should skip processing for gateway paths")
        void shouldSkipProcessingForGatewayPaths() throws IOException {
            when(uriInfo.getPath()).thenReturn("gateway/api/users");

            var filter = new AuthenticationFilter(providers);
            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(requestContext, never()).setProperty(any(), any());
        }

        @Test
        @DisplayName("should skip processing for health check paths")
        void shouldSkipProcessingForHealthPaths() throws IOException {
            when(uriInfo.getPath()).thenReturn("q/health");

            var filter = new AuthenticationFilter(providers);
            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }
    }
}
