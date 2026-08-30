package aussie.adapter.in.http;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import aussie.common.context.RouteContextAttributes;
import aussie.core.model.common.CorsConfig;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.service.ServiceRegistration;

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
        @DisplayName("should reject unlisted requested headers")
        void shouldRejectUnlistedRequestedHeaders() {
            when(request.getHeader("Access-Control-Request-Headers")).thenReturn("X-Not-Allowed");

            filter.corsHandler(rc);

            verify(response).setStatusCode(403);
        }

        @Test
        @DisplayName("should vary preflight responses by all request selectors")
        void shouldVaryPreflightResponses() {
            when(request.getHeader("Access-Control-Request-Method")).thenReturn("POST");

            filter.corsHandler(rc);

            verify(response).putHeader("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
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

        @ParameterizedTest
        @ValueSource(strings = {"/auth/session", "/admin/services", "/q/health", "/unknown"})
        @DisplayName("should reject a service origin on platform and unknown paths")
        void shouldRejectServiceOriginOutsideItsRoute(String path) {
            when(request.path()).thenReturn(path);
            when(rc.get(RouteContextAttributes.LOOKUP)).thenReturn(Optional.empty());
            when(request.getHeader("Origin")).thenReturn("https://service.example");
            when(request.getHeader("Access-Control-Request-Method")).thenReturn("POST");

            filter.corsHandler(rc);

            verify(response).setStatusCode(403);
        }

        @Test
        @DisplayName("should select methods and headers from the matched service")
        void shouldSelectMatchedServicePolicy() {
            final var alphaCors = CorsConfig.builder()
                    .allowedOrigins("https://shared.example")
                    .allowedMethods("GET")
                    .allowedHeaders("X-Alpha")
                    .build();
            final var betaCors = CorsConfig.builder()
                    .allowedOrigins("https://shared.example")
                    .allowedMethods("POST")
                    .allowedHeaders("X-Beta")
                    .build();
            when(request.getHeader("Origin")).thenReturn("https://shared.example");
            when(request.getHeader("Access-Control-Request-Method")).thenReturn("POST");
            when(request.getHeader("Access-Control-Request-Headers")).thenReturn("X-Beta");
            when(rc.get(RouteContextAttributes.LOOKUP)).thenReturn(Optional.of(route("alpha", alphaCors)));

            filter.corsHandler(rc);

            verify(response).setStatusCode(403);

            clearInvocations(response);
            when(rc.get(RouteContextAttributes.LOOKUP)).thenReturn(Optional.of(route("beta", betaCors)));

            filter.corsHandler(rc);

            verify(response).putHeader("Access-Control-Allow-Methods", "POST");
            verify(response).putHeader("Access-Control-Allow-Headers", "X-Beta");
            verify(response).setStatusCode(200);
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

        @ParameterizedTest
        @ValueSource(strings = {"/auth/session", "/admin/services", "/q/health", "/unknown"})
        @DisplayName("should not allow a service origin on platform and unknown paths")
        void shouldNotAllowServiceOriginOutsideItsRoute(String path) {
            when(request.path()).thenReturn(path);
            when(rc.get(RouteContextAttributes.LOOKUP)).thenReturn(Optional.empty());
            when(request.getHeader("Origin")).thenReturn("https://service.example");

            filter.corsHandler(rc);

            verify(response, never()).putHeader("Access-Control-Allow-Origin", "https://service.example");
            verify(rc).next();
        }

        @Test
        @DisplayName("should not apply one service policy to another service route")
        void shouldIsolateServicePoliciesByRoute() {
            final var betaCors = CorsConfig.builder()
                    .allowedOrigins("https://beta.example")
                    .allowCredentials(true)
                    .build();
            when(rc.get(RouteContextAttributes.LOOKUP)).thenReturn(Optional.of(route("beta", betaCors)));
            when(request.getHeader("Origin")).thenReturn("https://alpha.example");

            filter.corsHandler(rc);

            verify(response, never()).putHeader("Access-Control-Allow-Origin", "https://alpha.example");
            verify(rc).next();
        }

        @Test
        @DisplayName("should use a service policy for a matched route")
        void shouldUseServicePolicyForMatchedRoute() {
            final var serviceCors = CorsConfig.builder()
                    .allowedOrigins("https://service.example")
                    .allowCredentials(true)
                    .build();
            when(rc.get(RouteContextAttributes.LOOKUP)).thenReturn(Optional.of(route("service", serviceCors)));
            when(request.getHeader("Origin")).thenReturn("https://service.example");

            filter.corsHandler(rc);

            verify(response).putHeader("Access-Control-Allow-Origin", "https://service.example");
            verify(response).putHeader("Access-Control-Allow-Credentials", "true");
        }
    }

    private static RouteMatch route(String serviceId, CorsConfig corsConfig) {
        final var service = ServiceRegistration.builder(serviceId)
                .baseUrl(URI.create("http://service.example"))
                .corsConfig(corsConfig)
                .build();
        return new RouteMatch(
                service,
                new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty()),
                "/api/test",
                Map.of());
    }
}
