package aussie.adapter.in.http;

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

import aussie.core.model.common.CorsConfig;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.routing.ServiceRegistry;

@DisplayName("CorsFilter")
@SuppressWarnings("unchecked")
class CorsFilterTest {

    private Instance<GatewayCorsConfig> corsConfigInstance;
    private GatewayCorsConfig corsConfig;
    private RoutingContext rc;
    private HttpServerRequest request;
    private HttpServerResponse response;
    private ServiceRegistry serviceRegistry;
    private CorsFilter filter;

    @BeforeEach
    void setUp() {
        corsConfigInstance = mock(Instance.class);
        corsConfig = mock(GatewayCorsConfig.class);
        rc = mock(RoutingContext.class);
        request = mock(HttpServerRequest.class);
        response = mock(HttpServerResponse.class);
        serviceRegistry = mock(ServiceRegistry.class);

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

        filter = new CorsFilter(corsConfigInstance, serviceRegistry);
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

        @Test
        @DisplayName("should use a registered service policy for gateway auth preflight")
        void shouldUseRegisteredServicePolicyForGatewayAuthPreflight() {
            when(request.path()).thenReturn("/auth/session");
            when(request.getHeader("Origin")).thenReturn("http://localhost:3000");
            when(request.getHeader("Access-Control-Request-Method")).thenReturn("POST");
            when(request.getHeader("Access-Control-Request-Headers")).thenReturn("Content-Type");
            when(serviceRegistry.getCorsConfigForOriginFromLocalCache("http://localhost:3000"))
                    .thenReturn(Optional.of(demoCorsConfig()));

            filter.corsHandler(rc);

            verify(response).putHeader("Access-Control-Allow-Origin", "http://localhost:3000");
            verify(response).putHeader("Access-Control-Allow-Credentials", "true");
            verify(response).setStatusCode(200);
            verify(serviceRegistry, never()).findRoute("/auth/session", "POST");
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

        @Test
        @DisplayName("should use a registered service policy for gateway auth endpoints")
        void shouldUseRegisteredServicePolicyForGatewayAuth() {
            when(request.path()).thenReturn("/auth/session");
            when(request.getHeader("Origin")).thenReturn("http://localhost:3000");
            when(serviceRegistry.getCorsConfigForOriginFromLocalCache("http://localhost:3000"))
                    .thenReturn(Optional.of(demoCorsConfig()));

            filter.corsHandler(rc);

            verify(response).putHeader("Access-Control-Allow-Origin", "http://localhost:3000");
            verify(response).putHeader("Access-Control-Allow-Credentials", "true");
            verify(serviceRegistry, never()).findRoute("/auth/session", "GET");
        }

        @Test
        @DisplayName("should use a service policy for a matched route")
        void shouldUseServicePolicyForMatchedRoute() {
            var serviceCors = CorsConfig.builder()
                    .allowedOrigins("https://service.example")
                    .allowCredentials(true)
                    .build();
            var service = ServiceRegistration.builder("service")
                    .baseUrl(URI.create("http://service.example"))
                    .corsConfig(serviceCors)
                    .build();
            var route = new RouteMatch(
                    service,
                    new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty()),
                    "/api/test",
                    Map.of());
            when(serviceRegistry.findRoute("/api/test", "GET")).thenReturn(Optional.of(route));
            when(request.getHeader("Origin")).thenReturn("https://service.example");

            filter.corsHandler(rc);

            verify(response).putHeader("Access-Control-Allow-Origin", "https://service.example");
            verify(response).putHeader("Access-Control-Allow-Credentials", "true");
        }
    }

    private static CorsConfig demoCorsConfig() {
        return CorsConfig.builder()
                .allowedOrigins("http://localhost:3000")
                .allowedMethods("POST")
                .allowedHeaders("Content-Type")
                .allowCredentials(true)
                .build();
    }
}
