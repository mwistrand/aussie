package aussie.system.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.common.context.RouteContextAttributes;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.routing.ServiceOnlyMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.routing.ServiceRegistry;

@DisplayName("RouteResolutionFilter")
@ExtendWith(MockitoExtension.class)
class RouteResolutionFilterTest {

    @Mock
    private ServiceRegistry serviceRegistry;

    @Mock
    private RoutingContext rc;

    @Mock
    private HttpServerRequest request;

    private RouteResolutionFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RouteResolutionFilter(serviceRegistry);
    }

    private void stubRequest(String path, HttpMethod method) {
        when(rc.request()).thenReturn(request);
        when(request.path()).thenReturn(path);
        when(request.method()).thenReturn(method);
    }

    private ServiceRegistration service(String id, EndpointVisibility defaultVisibility) {
        return ServiceRegistration.builder(id)
                .displayName(id)
                .baseUrl(URI.create("http://localhost:8080"))
                .defaultVisibility(defaultVisibility)
                .build();
    }

    private RouteMatch publicRouteMatch() {
        var endpoint = EndpointConfig.publicEndpoint("/api/public", Set.of("GET"));
        return new RouteMatch(service("svc", EndpointVisibility.PRIVATE), endpoint, "/api/public", Map.of());
    }

    private RouteMatch privateRouteMatch() {
        var endpoint = EndpointConfig.privateEndpoint("/api/private", Set.of("GET"));
        return new RouteMatch(service("svc", EndpointVisibility.PUBLIC), endpoint, "/api/private", Map.of());
    }

    @Nested
    @DisplayName("PUBLIC route lookup")
    class PublicRoute {

        @Test
        @DisplayName("stashes lookup and PUBLIC flag, then calls next()")
        void publicRouteSetsBothKeys() {
            stubRequest("/svc/api/public", HttpMethod.GET);
            var match = publicRouteMatch();
            when(serviceRegistry.findRoute("/svc/api/public", "GET")).thenReturn(Optional.of(match));

            filter.resolveRoute(rc);

            ArgumentCaptor<Optional<RouteLookupResult>> captor = lookupCaptor();
            verify(rc).put(eq(RouteContextAttributes.LOOKUP), captor.capture());
            assertSame(match, captor.getValue().orElseThrow());
            verify(rc).put(RouteContextAttributes.PUBLIC, Boolean.TRUE);
            verify(rc).next();
        }
    }

    @Nested
    @DisplayName("PRIVATE route lookup")
    class PrivateRoute {

        @Test
        @DisplayName("stashes lookup but does not set PUBLIC flag")
        void privateRouteOmitsPublicFlag() {
            stubRequest("/svc/api/private", HttpMethod.GET);
            var match = privateRouteMatch();
            when(serviceRegistry.findRoute("/svc/api/private", "GET")).thenReturn(Optional.of(match));

            filter.resolveRoute(rc);

            verify(rc).put(eq(RouteContextAttributes.LOOKUP), any());
            verify(rc, never()).put(RouteContextAttributes.PUBLIC, Boolean.TRUE);
            verify(rc).next();
        }
    }

    @Nested
    @DisplayName("Service-only match (no endpoint)")
    class ServiceOnly {

        @Test
        @DisplayName("PUBLIC service default sets the flag")
        void publicServiceDefaultSetsFlag() {
            stubRequest("/svc/unmatched", HttpMethod.GET);
            var svc = service("svc", EndpointVisibility.PUBLIC);
            var match = new ServiceOnlyMatch(svc);
            when(serviceRegistry.findRoute("/svc/unmatched", "GET")).thenReturn(Optional.of(match));

            filter.resolveRoute(rc);

            verify(rc).put(RouteContextAttributes.PUBLIC, Boolean.TRUE);
            verify(rc).next();
        }

        @Test
        @DisplayName("PRIVATE service default omits the flag")
        void privateServiceDefaultOmitsFlag() {
            stubRequest("/svc/unmatched", HttpMethod.GET);
            var svc = service("svc", EndpointVisibility.PRIVATE);
            var match = new ServiceOnlyMatch(svc);
            when(serviceRegistry.findRoute("/svc/unmatched", "GET")).thenReturn(Optional.of(match));

            filter.resolveRoute(rc);

            verify(rc, never()).put(RouteContextAttributes.PUBLIC, Boolean.TRUE);
            verify(rc).next();
        }
    }

    @Nested
    @DisplayName("No match")
    class NoMatch {

        @Test
        @DisplayName("stashes empty lookup, does not set PUBLIC flag, still calls next()")
        void emptyLookupDoesNotSetPublic() {
            stubRequest("/admin/health", HttpMethod.GET);
            when(serviceRegistry.findRoute("/admin/health", "GET")).thenReturn(Optional.empty());

            filter.resolveRoute(rc);

            ArgumentCaptor<Optional<RouteLookupResult>> captor = lookupCaptor();
            verify(rc).put(eq(RouteContextAttributes.LOOKUP), captor.capture());
            assertEquals(Optional.empty(), captor.getValue());
            verify(rc, never()).put(RouteContextAttributes.PUBLIC, Boolean.TRUE);
            verify(rc).next();
        }
    }

    @Nested
    @DisplayName("Constants are stable")
    class Constants {

        @Test
        @DisplayName("route context attribute values do not change without coordination")
        void keysAreStable() {
            assertEquals("aussie.route.lookup", RouteContextAttributes.LOOKUP);
            assertEquals("aussie.route.public", RouteContextAttributes.PUBLIC);
        }
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Optional<RouteLookupResult>> lookupCaptor() {
        return ArgumentCaptor.forClass(Optional.class);
    }
}
