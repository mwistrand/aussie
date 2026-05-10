package aussie.system.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import aussie.core.model.auth.AuthenticationContext;
import aussie.core.model.auth.AuthenticationResult;
import aussie.core.model.auth.Principal;
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
@SuppressWarnings({"unchecked", "deprecation"})
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

    @AfterEach
    void tearDown() {
        System.clearProperty("aussie.auth.use-legacy-filter");
        System.clearProperty("aussie.auth.enabled");
        System.clearProperty("aussie.auth.admin-paths-only");
    }

    private void enableLegacyFilter() {
        System.setProperty("aussie.auth.use-legacy-filter", "true");
    }

    private AuthenticationProvider mockProvider(
            String name, int priority, boolean available, AuthenticationResult result) {
        var provider = mock(AuthenticationProvider.class);
        when(provider.name()).thenReturn(name);
        when(provider.priority()).thenReturn(priority);
        when(provider.isAvailable()).thenReturn(available);
        when(provider.authenticate(any(), any())).thenReturn(result);
        return provider;
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

    @Nested
    @DisplayName("when legacy filter is enabled")
    class LegacyFilterEnabledTests {

        @BeforeEach
        void setUp() {
            enableLegacyFilter();
        }

        @Test
        @DisplayName("should skip non-admin paths when admin-paths-only is true (default)")
        void shouldSkipNonAdminPaths() throws IOException {
            when(uriInfo.getPath()).thenReturn("gateway/api/users");

            var filter = new AuthenticationFilter(providers);
            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(providers, never()).stream();
        }

        @Test
        @DisplayName("should process admin paths when admin-paths-only is true")
        void shouldProcessAdminPaths() throws IOException {
            when(uriInfo.getPath()).thenReturn("admin/services");
            when(providers.stream()).thenReturn(Stream.empty());

            var filter = new AuthenticationFilter(providers);
            filter.filter(requestContext);

            // No providers available → abort with 500
            verify(requestContext).abortWith(any());
        }

        @Test
        @DisplayName("should process all paths when admin-paths-only is false")
        void shouldProcessAllPathsWhenAdminPathsOnlyFalse() throws IOException {
            System.setProperty("aussie.auth.admin-paths-only", "false");
            when(uriInfo.getPath()).thenReturn("gateway/api/users");
            when(providers.stream()).thenReturn(Stream.empty());

            var filter = new AuthenticationFilter(providers);
            filter.filter(requestContext);

            // No providers → abort with 500
            verify(requestContext).abortWith(any());
        }

        @Test
        @DisplayName("should skip when auth is disabled entirely")
        void shouldSkipWhenAuthDisabled() throws IOException {
            System.setProperty("aussie.auth.enabled", "false");
            when(uriInfo.getPath()).thenReturn("admin/services");

            var filter = new AuthenticationFilter(providers);
            filter.filter(requestContext);

            verify(requestContext, never()).abortWith(any());
            verify(providers, never()).stream();
        }

        @Test
        @DisplayName("should abort with 500 when no providers are available")
        void shouldAbortWith500WhenNoProviders() throws IOException {
            when(uriInfo.getPath()).thenReturn("admin/services");
            when(providers.stream()).thenReturn(Stream.empty());

            var filter = new AuthenticationFilter(providers);
            filter.filter(requestContext);

            ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
            verify(requestContext).abortWith(responseCaptor.capture());
            assertEquals(500, responseCaptor.getValue().getStatus());
        }

        @Test
        @DisplayName("should abort with 500 when all providers are unavailable")
        void shouldAbortWith500WhenAllProvidersUnavailable() throws IOException {
            when(uriInfo.getPath()).thenReturn("admin/services");
            var unavailable = mockProvider("test", 100, false, AuthenticationResult.Skip.instance());
            when(providers.stream()).thenReturn(Stream.of(unavailable));

            var filter = new AuthenticationFilter(providers);
            filter.filter(requestContext);

            ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
            verify(requestContext).abortWith(responseCaptor.capture());
            assertEquals(500, responseCaptor.getValue().getStatus());
        }

        @Test
        @DisplayName("should set auth context on successful authentication")
        void shouldSetAuthContextOnSuccess() throws IOException {
            when(uriInfo.getPath()).thenReturn("admin/services");

            var principal = new Principal("user-1", "Test User", "api-key", Map.of());
            var context = new AuthenticationContext(
                    principal,
                    Set.of("admin"),
                    Map.of(),
                    Instant.now(),
                    Instant.now().plusSeconds(3600));
            var provider = mockProvider("test-provider", 100, true, new AuthenticationResult.Success(context));
            when(providers.stream()).thenReturn(Stream.of(provider));

            var filter = new AuthenticationFilter(providers);
            filter.filter(requestContext);

            verify(requestContext).setProperty(eq(AuthenticationFilter.AUTH_CONTEXT_PROPERTY), eq(context));
            verify(requestContext, never()).abortWith(any());
        }

        @Test
        @DisplayName("should abort with failure status when authentication fails")
        void shouldAbortOnAuthFailure() throws IOException {
            when(uriInfo.getPath()).thenReturn("admin/services");

            var provider = mockProvider(
                    "test-provider", 100, true, AuthenticationResult.Failure.unauthorized("Invalid API key"));
            when(providers.stream()).thenReturn(Stream.of(provider));

            var filter = new AuthenticationFilter(providers);
            filter.filter(requestContext);

            ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
            verify(requestContext).abortWith(responseCaptor.capture());
            assertEquals(401, responseCaptor.getValue().getStatus());
        }

        @Test
        @DisplayName("should try next provider when current one skips")
        void shouldTryNextProviderOnSkip() throws IOException {
            when(uriInfo.getPath()).thenReturn("admin/services");

            var principal = new Principal("user-1", "Test User", "api-key", Map.of());
            var context = new AuthenticationContext(
                    principal,
                    Set.of("admin"),
                    Map.of(),
                    Instant.now(),
                    Instant.now().plusSeconds(3600));

            var skippingProvider = mockProvider("skipper", 200, true, AuthenticationResult.Skip.instance());
            var successProvider = mockProvider("authenticator", 100, true, new AuthenticationResult.Success(context));
            when(providers.stream()).thenReturn(Stream.of(skippingProvider, successProvider));

            var filter = new AuthenticationFilter(providers);
            filter.filter(requestContext);

            verify(requestContext).setProperty(eq(AuthenticationFilter.AUTH_CONTEXT_PROPERTY), eq(context));
            verify(requestContext, never()).abortWith(any());
        }

        @Test
        @DisplayName("should abort with 401 when all providers skip")
        void shouldAbortWith401WhenAllProvidersSkip() throws IOException {
            when(uriInfo.getPath()).thenReturn("admin/services");

            var provider1 = mockProvider("provider-1", 200, true, AuthenticationResult.Skip.instance());
            var provider2 = mockProvider("provider-2", 100, true, AuthenticationResult.Skip.instance());
            when(providers.stream()).thenReturn(Stream.of(provider1, provider2));

            var filter = new AuthenticationFilter(providers);
            filter.filter(requestContext);

            ArgumentCaptor<Response> responseCaptor = ArgumentCaptor.forClass(Response.class);
            verify(requestContext).abortWith(responseCaptor.capture());
            assertEquals(401, responseCaptor.getValue().getStatus());
        }

        @Test
        @DisplayName("should try providers in priority order (highest first)")
        void shouldTryProvidersInPriorityOrder() throws IOException {
            when(uriInfo.getPath()).thenReturn("admin/services");

            // Low priority provider that would fail
            var lowPriority =
                    mockProvider("low", 50, true, AuthenticationResult.Failure.unauthorized("Should not reach"));

            // High priority provider that succeeds
            var principal = new Principal("user-1", "Admin", "api-key", Map.of());
            var context = new AuthenticationContext(
                    principal,
                    Set.of("admin"),
                    Map.of(),
                    Instant.now(),
                    Instant.now().plusSeconds(3600));
            var highPriority = mockProvider("high", 200, true, new AuthenticationResult.Success(context));

            // Stream in wrong order — filter should sort by priority
            when(providers.stream()).thenReturn(Stream.of(lowPriority, highPriority));

            var filter = new AuthenticationFilter(providers);
            filter.filter(requestContext);

            // High priority provider should handle it
            verify(requestContext).setProperty(eq(AuthenticationFilter.AUTH_CONTEXT_PROPERTY), eq(context));
            verify(requestContext, never()).abortWith(any());
            // Low priority provider should not have been called
            verify(lowPriority, never()).authenticate(any(), any());
        }
    }
}
