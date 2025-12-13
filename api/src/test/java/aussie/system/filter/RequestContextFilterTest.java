package aussie.system.filter;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.EndpointConfig;
import aussie.core.model.EndpointVisibility;
import aussie.core.model.RouteMatch;
import aussie.core.model.ServiceRegistration;
import aussie.core.service.ServiceRegistry;

@DisplayName("RequestContextFilter")
class RequestContextFilterTest {

    private RequestContextFilter filter;
    private ServiceRegistry serviceRegistry;
    private ContainerRequestContext requestContext;
    private UriInfo uriInfo;

    @BeforeEach
    void setUp() {
        serviceRegistry = mock(ServiceRegistry.class);
        filter = new RequestContextFilter(serviceRegistry);
        requestContext = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(requestContext.getMethod()).thenReturn("GET");
    }

    private ServiceRegistration createTestService(String serviceId) {
        return ServiceRegistration.builder(serviceId)
                .displayName(serviceId)
                .baseUrl(URI.create("http://localhost:8081"))
                .endpoints(List.of(new EndpointConfig(
                        "/api/users", Set.of("GET", "POST"), EndpointVisibility.PUBLIC, Optional.empty())))
                .build();
    }

    private RouteMatch createRouteMatch(String serviceId, String endpointPath) {
        var service = createTestService(serviceId);
        var endpoint = new EndpointConfig(endpointPath, Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty());
        return new RouteMatch(service, endpoint, endpointPath, Map.of());
    }

    @Nested
    @DisplayName("When route is found")
    class RouteMatchTests {

        @Test
        @DisplayName("should set RouteMatch property when route is found")
        void shouldSetRouteMatchProperty() {
            var routeMatch = createRouteMatch("demo-service", "/api/users");
            when(uriInfo.getPath()).thenReturn("/demo-service/api/users");
            when(serviceRegistry.findRoute("/demo-service/api/users", "GET")).thenReturn(Optional.of(routeMatch));

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.ROUTE_MATCH_PROPERTY, routeMatch);
        }

        @Test
        @DisplayName("should extract service ID from RouteMatch")
        void shouldExtractServiceIdFromRouteMatch() {
            var routeMatch = createRouteMatch("demo-service", "/api/users");
            when(uriInfo.getPath()).thenReturn("/demo-service/api/users");
            when(serviceRegistry.findRoute("/demo-service/api/users", "GET")).thenReturn(Optional.of(routeMatch));

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "demo-service");
        }

        @Test
        @DisplayName("should extract endpoint path from RouteMatch")
        void shouldExtractEndpointPathFromRouteMatch() {
            var routeMatch = createRouteMatch("demo-service", "/api/users");
            when(uriInfo.getPath()).thenReturn("/demo-service/api/users");
            when(serviceRegistry.findRoute("/demo-service/api/users", "GET")).thenReturn(Optional.of(routeMatch));

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.ENDPOINT_PATH_PROPERTY, "/api/users");
        }

        @Test
        @DisplayName("should use correct HTTP method for route lookup")
        void shouldUseCorrectHttpMethod() {
            var routeMatch = createRouteMatch("demo-service", "/api/users");
            when(uriInfo.getPath()).thenReturn("/demo-service/api/users");
            when(requestContext.getMethod()).thenReturn("POST");
            when(serviceRegistry.findRoute("/demo-service/api/users", "POST")).thenReturn(Optional.of(routeMatch));

            filter.filter(requestContext);

            verify(serviceRegistry).findRoute("/demo-service/api/users", "POST");
        }

        @Test
        @DisplayName("should handle nested endpoint paths")
        void shouldHandleNestedEndpointPaths() {
            var routeMatch = createRouteMatch("demo-service", "/api/users/{id}/profile");
            when(uriInfo.getPath()).thenReturn("/demo-service/api/users/123/profile");
            when(serviceRegistry.findRoute("/demo-service/api/users/123/profile", "GET"))
                    .thenReturn(Optional.of(routeMatch));

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "demo-service");
            verify(requestContext).setProperty(RequestContextFilter.ENDPOINT_PATH_PROPERTY, "/api/users/{id}/profile");
        }
    }

    @Nested
    @DisplayName("When no route is found (fallback extraction)")
    class FallbackExtractionTests {

        @BeforeEach
        void setUpNoRouteMatch() {
            when(serviceRegistry.findRoute(anyString(), anyString())).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("should not set RouteMatch property when no route found")
        void shouldNotSetRouteMatchProperty() {
            when(uriInfo.getPath()).thenReturn("/unknown-service/api/test");

            filter.filter(requestContext);

            verify(requestContext, never())
                    .setProperty(
                            org.mockito.ArgumentMatchers.eq(RequestContextFilter.ROUTE_MATCH_PROPERTY),
                            org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("should extract service ID from path when no route found")
        void shouldExtractServiceIdFromPath() {
            when(uriInfo.getPath()).thenReturn("/demo-service/api/users");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "demo-service");
        }

        @Test
        @DisplayName("should extract service ID without leading slash")
        void shouldExtractServiceIdWithoutLeadingSlash() {
            when(uriInfo.getPath()).thenReturn("demo-service/api/users");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "demo-service");
        }

        @Test
        @DisplayName("should extract service ID when path has only service ID")
        void shouldExtractServiceIdWhenOnlyServiceId() {
            when(uriInfo.getPath()).thenReturn("/demo-service");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "demo-service");
        }

        @Test
        @DisplayName("should extract service ID with trailing slash")
        void shouldExtractServiceIdWithTrailingSlash() {
            when(uriInfo.getPath()).thenReturn("/demo-service/");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "demo-service");
        }

        @Test
        @DisplayName("should extract first segment for admin paths")
        void shouldExtractFirstSegmentForAdminPaths() {
            when(uriInfo.getPath()).thenReturn("/admin/services");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "admin");
        }

        @Test
        @DisplayName("should extract first segment for gateway paths")
        void shouldExtractFirstSegmentForGatewayPaths() {
            when(uriInfo.getPath()).thenReturn("/gateway/route/test");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "gateway");
        }

        @Test
        @DisplayName("should extract first segment for health check paths")
        void shouldExtractFirstSegmentForHealthPaths() {
            when(uriInfo.getPath()).thenReturn("/q/health");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "q");
        }

        @Test
        @DisplayName("should return 'unknown' for null path")
        void shouldReturnUnknownForNullPath() {
            when(uriInfo.getPath()).thenReturn(null);

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "unknown");
        }

        @Test
        @DisplayName("should return 'unknown' for empty path")
        void shouldReturnUnknownForEmptyPath() {
            when(uriInfo.getPath()).thenReturn("");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "unknown");
        }

        @Test
        @DisplayName("should return 'unknown' for root path")
        void shouldReturnUnknownForRootPath() {
            when(uriInfo.getPath()).thenReturn("/");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "unknown");
        }

        @Test
        @DisplayName("should preserve original case of service ID")
        void shouldPreserveOriginalCaseOfServiceId() {
            when(uriInfo.getPath()).thenReturn("/Demo-Service/api/users");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "Demo-Service");
        }
    }

    @Nested
    @DisplayName("Endpoint path fallback extraction")
    class EndpointPathFallbackTests {

        @BeforeEach
        void setUpNoRouteMatch() {
            when(serviceRegistry.findRoute(anyString(), anyString())).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("should extract endpoint path from path")
        void shouldExtractEndpointPath() {
            when(uriInfo.getPath()).thenReturn("/demo-service/api/users");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.ENDPOINT_PATH_PROPERTY, "/api/users");
        }

        @Test
        @DisplayName("should extract nested endpoint path")
        void shouldExtractNestedEndpointPath() {
            when(uriInfo.getPath()).thenReturn("/demo-service/api/users/123/profile");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.ENDPOINT_PATH_PROPERTY, "/api/users/123/profile");
        }

        @Test
        @DisplayName("should return '/' when path has only service ID")
        void shouldReturnRootWhenOnlyServiceId() {
            when(uriInfo.getPath()).thenReturn("/demo-service");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.ENDPOINT_PATH_PROPERTY, "/");
        }

        @Test
        @DisplayName("should return '/' when path has service ID with trailing slash")
        void shouldReturnRootWhenServiceIdWithTrailingSlash() {
            when(uriInfo.getPath()).thenReturn("/demo-service/");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.ENDPOINT_PATH_PROPERTY, "/");
        }

        @Test
        @DisplayName("should return '/' for null path")
        void shouldReturnRootForNullPath() {
            when(uriInfo.getPath()).thenReturn(null);

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.ENDPOINT_PATH_PROPERTY, "/");
        }

        @Test
        @DisplayName("should return '/' for empty path")
        void shouldReturnRootForEmptyPath() {
            when(uriInfo.getPath()).thenReturn("");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.ENDPOINT_PATH_PROPERTY, "/");
        }

        @Test
        @DisplayName("should handle path without leading slash")
        void shouldHandlePathWithoutLeadingSlash() {
            when(uriInfo.getPath()).thenReturn("demo-service/api/users");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.ENDPOINT_PATH_PROPERTY, "/api/users");
        }

        @Test
        @DisplayName("should extract endpoint path for gateway routes")
        void shouldExtractEndpointPathForGatewayRoutes() {
            when(uriInfo.getPath()).thenReturn("/admin/services/demo");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.ENDPOINT_PATH_PROPERTY, "/services/demo");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCaseTests {

        @BeforeEach
        void setUpNoRouteMatch() {
            when(serviceRegistry.findRoute(anyString(), anyString())).thenReturn(Optional.empty());
        }

        @Test
        @DisplayName("should handle path with special characters in service ID")
        void shouldHandleSpecialCharactersInServiceId() {
            when(uriInfo.getPath()).thenReturn("/my-service-v2/api/test");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "my-service-v2");
            verify(requestContext).setProperty(RequestContextFilter.ENDPOINT_PATH_PROPERTY, "/api/test");
        }

        @Test
        @DisplayName("should handle path with query string segments in path")
        void shouldHandleComplexPaths() {
            when(uriInfo.getPath()).thenReturn("/service/api/users/search");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "service");
            verify(requestContext).setProperty(RequestContextFilter.ENDPOINT_PATH_PROPERTY, "/api/users/search");
        }

        @Test
        @DisplayName("should handle single segment that is not a gateway path")
        void shouldHandleSingleNonGatewaySegment() {
            when(uriInfo.getPath()).thenReturn("myservice");

            filter.filter(requestContext);

            verify(requestContext).setProperty(RequestContextFilter.SERVICE_ID_PROPERTY, "myservice");
            verify(requestContext).setProperty(RequestContextFilter.ENDPOINT_PATH_PROPERTY, "/");
        }
    }
}
