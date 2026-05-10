package aussie.adapter.in.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import aussie.core.config.SessionConfig;
import aussie.system.filter.RouteResolutionFilter;

@DisplayName("ConflictingAuthFilter")
@SuppressWarnings("unchecked")
class ConflictingAuthFilterTest {

    private ContainerRequestContext requestContext;
    private Instance<SessionConfig> sessionConfigInstance;
    private Instance<SessionCookieManager> cookieManagerInstance;
    private RoutingContext routingContext;
    private SessionConfig sessionConfig;
    private SessionCookieManager cookieManager;
    private HttpServerRequest httpRequest;
    private UriInfo uriInfo;

    @BeforeEach
    void setUp() {
        requestContext = mock(ContainerRequestContext.class);
        sessionConfigInstance = mock(Instance.class);
        cookieManagerInstance = mock(Instance.class);
        routingContext = mock(RoutingContext.class);
        sessionConfig = mock(SessionConfig.class);
        cookieManager = mock(SessionCookieManager.class);
        httpRequest = mock(HttpServerRequest.class);
        uriInfo = mock(UriInfo.class);

        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/test");
        when(routingContext.request()).thenReturn(httpRequest);
    }

    private ConflictingAuthFilter createFilter() {
        return new ConflictingAuthFilter(sessionConfigInstance, cookieManagerInstance, routingContext);
    }

    @Nested
    @DisplayName("when the resolved route is PUBLIC")
    class PublicRoute {

        @Test
        @DisplayName("should skip filtering even when both auth header and cookie are present")
        void shouldSkipFiltering() {
            when(routingContext.get(RouteResolutionFilter.PUBLIC_KEY)).thenReturn(Boolean.TRUE);

            createFilter().filter(requestContext);

            verify(sessionConfigInstance, never()).isResolvable();
            verify(requestContext, never()).abortWith(any());
        }
    }

    @Nested
    @DisplayName("when session config is not resolvable")
    class SessionConfigNotResolvable {

        @Test
        @DisplayName("should skip filtering")
        void shouldSkipFiltering() {
            when(sessionConfigInstance.isResolvable()).thenReturn(false);

            createFilter().filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }
    }

    @Nested
    @DisplayName("when sessions are disabled")
    class SessionsDisabled {

        @Test
        @DisplayName("should skip filtering")
        void shouldSkipFiltering() {
            when(sessionConfigInstance.isResolvable()).thenReturn(true);
            when(sessionConfigInstance.get()).thenReturn(sessionConfig);
            when(sessionConfig.enabled()).thenReturn(false);

            createFilter().filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }
    }

    @Nested
    @DisplayName("when cookie manager is not resolvable")
    class CookieManagerNotResolvable {

        @Test
        @DisplayName("should skip filtering")
        void shouldSkipFiltering() {
            when(sessionConfigInstance.isResolvable()).thenReturn(true);
            when(sessionConfigInstance.get()).thenReturn(sessionConfig);
            when(sessionConfig.enabled()).thenReturn(true);
            when(cookieManagerInstance.isResolvable()).thenReturn(false);

            createFilter().filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }
    }

    @Nested
    @DisplayName("when both auth header and session cookie are present")
    class ConflictingAuth {

        @BeforeEach
        void setUp() {
            when(sessionConfigInstance.isResolvable()).thenReturn(true);
            when(sessionConfigInstance.get()).thenReturn(sessionConfig);
            when(sessionConfig.enabled()).thenReturn(true);
            when(cookieManagerInstance.isResolvable()).thenReturn(true);
            when(cookieManagerInstance.get()).thenReturn(cookieManager);
        }

        @Test
        @DisplayName("should abort with 400 Bad Request")
        void shouldAbortWith400() {
            when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer some-token");
            when(cookieManager.hasSessionCookie(httpRequest)).thenReturn(true);

            createFilter().filter(requestContext);

            var captor = ArgumentCaptor.forClass(Response.class);
            verify(requestContext).abortWith(captor.capture());
            assertEquals(400, captor.getValue().getStatus());
        }
    }

    @Nested
    @DisplayName("when only auth header is present")
    class OnlyAuthHeader {

        @Test
        @DisplayName("should allow request to proceed")
        void shouldAllowRequest() {
            when(sessionConfigInstance.isResolvable()).thenReturn(true);
            when(sessionConfigInstance.get()).thenReturn(sessionConfig);
            when(sessionConfig.enabled()).thenReturn(true);
            when(cookieManagerInstance.isResolvable()).thenReturn(true);
            when(cookieManagerInstance.get()).thenReturn(cookieManager);
            when(requestContext.getHeaderString("Authorization")).thenReturn("Bearer some-token");
            when(cookieManager.hasSessionCookie(httpRequest)).thenReturn(false);

            createFilter().filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }
    }

    @Nested
    @DisplayName("when only session cookie is present")
    class OnlySessionCookie {

        @Test
        @DisplayName("should allow request to proceed")
        void shouldAllowRequest() {
            when(sessionConfigInstance.isResolvable()).thenReturn(true);
            when(sessionConfigInstance.get()).thenReturn(sessionConfig);
            when(sessionConfig.enabled()).thenReturn(true);
            when(cookieManagerInstance.isResolvable()).thenReturn(true);
            when(cookieManagerInstance.get()).thenReturn(cookieManager);
            when(requestContext.getHeaderString("Authorization")).thenReturn(null);
            when(cookieManager.hasSessionCookie(httpRequest)).thenReturn(true);

            createFilter().filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }
    }

    @Nested
    @DisplayName("when neither auth header nor session cookie is present")
    class NoAuth {

        @Test
        @DisplayName("should allow request to proceed")
        void shouldAllowRequest() {
            when(sessionConfigInstance.isResolvable()).thenReturn(true);
            when(sessionConfigInstance.get()).thenReturn(sessionConfig);
            when(sessionConfig.enabled()).thenReturn(true);
            when(cookieManagerInstance.isResolvable()).thenReturn(true);
            when(cookieManagerInstance.get()).thenReturn(cookieManager);
            when(requestContext.getHeaderString("Authorization")).thenReturn(null);
            when(cookieManager.hasSessionCookie(httpRequest)).thenReturn(false);

            createFilter().filter(requestContext);

            verify(requestContext, never()).abortWith(any());
        }
    }
}
