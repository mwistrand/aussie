package aussie.adapter.in.http;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.inject.Instance;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CorsFilter")
@SuppressWarnings("unchecked")
class CorsFilterTest {

    private Instance<GatewayCorsConfig> corsConfigInstance;
    private GatewayCorsConfig corsConfig;
    private RoutingContext rc;
    private HttpServerRequest request;
    private HttpServerResponse response;
    private CorsFilter filter;

    @BeforeEach
    void setUp() {
        corsConfigInstance = mock(Instance.class);
        corsConfig = mock(GatewayCorsConfig.class);
        rc = mock(RoutingContext.class);
        request = mock(HttpServerRequest.class);
        response = mock(HttpServerResponse.class);

        when(rc.request()).thenReturn(request);
        when(rc.response()).thenReturn(response);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.path()).thenReturn("/api/test");

        when(corsConfigInstance.isResolvable()).thenReturn(true);
        when(corsConfigInstance.get()).thenReturn(corsConfig);
        when(corsConfig.enabled()).thenReturn(true);
        when(corsConfig.allowedOrigins()).thenReturn(List.of("https://example.com"));
        when(corsConfig.allowedMethods()).thenReturn(Set.of("GET", "POST", "PUT", "DELETE"));
        when(corsConfig.allowedHeaders()).thenReturn(Set.of("Content-Type", "Authorization"));
        when(corsConfig.exposedHeaders()).thenReturn(Optional.empty());
        when(corsConfig.allowCredentials()).thenReturn(true);
        when(corsConfig.maxAge()).thenReturn(Optional.of(3600L));

        when(response.putHeader(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(response);
        when(response.setStatusCode(org.mockito.ArgumentMatchers.anyInt())).thenReturn(response);

        filter = new CorsFilter(corsConfigInstance);
    }

    @Nested
    @DisplayName("when CORS config not resolvable")
    class ConfigNotResolvable {

        @Test
        @DisplayName("should skip and call next")
        void shouldSkipAndCallNext() {
            when(corsConfigInstance.isResolvable()).thenReturn(false);

            filter.corsHandler(rc);

            verify(rc).next();
        }
    }

    @Nested
    @DisplayName("when CORS disabled")
    class CorsDisabled {

        @Test
        @DisplayName("should skip and call next")
        void shouldSkipAndCallNext() {
            when(corsConfig.enabled()).thenReturn(false);

            filter.corsHandler(rc);

            verify(rc).next();
        }
    }

    @Nested
    @DisplayName("when no Origin header")
    class NoOriginHeader {

        @Test
        @DisplayName("should skip and call next")
        void shouldSkipAndCallNext() {
            when(request.getHeader("Origin")).thenReturn(null);

            filter.corsHandler(rc);

            verify(rc).next();
        }

        @Test
        @DisplayName("should skip when origin is blank")
        void shouldSkipWhenOriginBlank() {
            when(request.getHeader("Origin")).thenReturn("");

            filter.corsHandler(rc);

            verify(rc).next();
        }
    }

    @Nested
    @DisplayName("preflight requests")
    class PreflightTests {

        @BeforeEach
        void setUpPreflight() {
            when(request.method()).thenReturn(HttpMethod.OPTIONS);
            when(request.getHeader("Origin")).thenReturn("https://example.com");
        }

        @Test
        @DisplayName("should accept valid preflight request")
        void shouldAcceptValidPreflight() {
            when(request.getHeader("Access-Control-Request-Method")).thenReturn("POST");

            filter.corsHandler(rc);

            verify(response).putHeader("Access-Control-Allow-Origin", "https://example.com");
            verify(response).putHeader("Access-Control-Allow-Credentials", "true");
            verify(response).setStatusCode(200);
            verify(rc, never()).next();
        }

        @Test
        @DisplayName("should reject preflight from disallowed origin")
        void shouldRejectDisallowedOrigin() {
            when(request.getHeader("Origin")).thenReturn("https://evil.com");
            when(request.getHeader("Access-Control-Request-Method")).thenReturn("POST");

            filter.corsHandler(rc);

            verify(response).setStatusCode(403);
            verify(rc, never()).next();
        }

        @Test
        @DisplayName("should reject preflight with disallowed method")
        void shouldRejectDisallowedMethod() {
            when(request.getHeader("Access-Control-Request-Method")).thenReturn("TRACE");

            filter.corsHandler(rc);

            verify(response).setStatusCode(403);
        }

        @Test
        @DisplayName("should set wildcard origin when * configured and no credentials")
        void shouldSetWildcardOrigin() {
            when(corsConfig.allowedOrigins()).thenReturn(List.of("*"));
            when(corsConfig.allowCredentials()).thenReturn(false);
            when(request.getHeader("Access-Control-Request-Method")).thenReturn(null);

            filter.corsHandler(rc);

            verify(response).putHeader("Access-Control-Allow-Origin", "*");
            verify(response, never()).putHeader("Access-Control-Allow-Credentials", "true");
        }

        @Test
        @DisplayName("should set max age header when configured")
        void shouldSetMaxAgeHeader() {
            when(request.getHeader("Access-Control-Request-Method")).thenReturn(null);

            filter.corsHandler(rc);

            verify(response).putHeader("Access-Control-Max-Age", "3600");
        }
    }

    @Nested
    @DisplayName("non-preflight CORS requests")
    class NonPreflightTests {

        @BeforeEach
        void setUpNonPreflight() {
            when(request.getHeader("Origin")).thenReturn("https://example.com");
        }

        @Test
        @DisplayName("should add CORS headers and call next")
        void shouldAddHeadersAndCallNext() {
            filter.corsHandler(rc);

            verify(response).putHeader("Access-Control-Allow-Origin", "https://example.com");
            verify(response).putHeader("Access-Control-Allow-Credentials", "true");
            verify(response).putHeader("Vary", "Origin");
            verify(rc).next();
        }

        @Test
        @DisplayName("should not add headers for disallowed origin")
        void shouldNotAddHeadersForDisallowedOrigin() {
            when(request.getHeader("Origin")).thenReturn("https://evil.com");

            filter.corsHandler(rc);

            verify(response, never()).putHeader("Access-Control-Allow-Origin", "https://evil.com");
            verify(rc).next();
        }

        @Test
        @DisplayName("should add exposed headers when configured")
        void shouldAddExposedHeaders() {
            when(corsConfig.exposedHeaders()).thenReturn(Optional.of(Set.of("X-Custom-Header")));

            filter.corsHandler(rc);

            verify(response).putHeader("Access-Control-Expose-Headers", "X-Custom-Header");
        }

        @Test
        @DisplayName("should use wildcard when * origin and no credentials")
        void shouldUseWildcardWhenNoCredentials() {
            when(corsConfig.allowedOrigins()).thenReturn(List.of("*"));
            when(corsConfig.allowCredentials()).thenReturn(false);

            filter.corsHandler(rc);

            verify(response).putHeader("Access-Control-Allow-Origin", "*");
        }
    }
}
