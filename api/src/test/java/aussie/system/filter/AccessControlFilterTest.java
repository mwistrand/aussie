package aussie.system.filter;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.ws.rs.container.ContainerRequestContext;

import io.quarkiverse.httpproblem.HttpProblem;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.common.context.ClientContext;
import aussie.common.context.RouteContextAttributes;
import aussie.core.model.common.SourceIdentifier;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.routing.ServiceOnlyMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.SecurityMonitoring;
import aussie.core.service.auth.AccessControlEvaluator;

@DisplayName("AccessControlFilter")
@ExtendWith(MockitoExtension.class)
class AccessControlFilterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private ClientContextResolver clientContextResolver;

    @Mock
    private AccessControlEvaluator accessEvaluator;

    @Mock
    private SecurityMonitoring securityMonitoring;

    @Mock
    private ContainerRequestContext requestContext;

    @Mock
    private HttpServerRequest vertxRequest;

    private AccessControlFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AccessControlFilter(clientContextResolver, accessEvaluator, securityMonitoring);
    }

    private ServiceRegistration createService(String serviceId) {
        return ServiceRegistration.builder(serviceId)
                .displayName(serviceId)
                .baseUrl(URI.create("http://localhost:8080"))
                .defaultVisibility(EndpointVisibility.PUBLIC)
                .build();
    }

    private ServiceOnlyMatch createServiceOnlyMatch(ServiceRegistration service) {
        return new ServiceOnlyMatch(service);
    }

    private RouteMatch createRouteMatch(ServiceRegistration service) {
        var endpoint = EndpointConfig.publicEndpoint("/api/users", Set.of("GET"));
        return new RouteMatch(service, endpoint, "/api/users", Map.of());
    }

    private void setupLookup(Optional<? extends RouteLookupResult> lookup) {
        when(requestContext.getProperty(RouteContextAttributes.LOOKUP)).thenReturn(lookup);
    }

    @Test
    @DisplayName("should allow request when route found and access allowed")
    void routeFoundAndAllowed() {
        var service = createService("test-service");
        var routeResult = createRouteMatch(service);

        setupLookup(Optional.of(routeResult));

        var source = SourceIdentifier.of("192.168.1.1");
        when(clientContextResolver.getOrCompute(requestContext, vertxRequest))
                .thenReturn(new ClientContext("192.168.1.1", false, null));
        when(accessEvaluator.isAllowed(source, routeResult, service.accessConfig()))
                .thenReturn(true);

        var result = filter.filter(requestContext, vertxRequest).await().atMost(TIMEOUT);

        assertNull(result);
    }

    @Test
    @DisplayName("should throw HttpProblem when route found but access denied")
    void routeFoundAndDenied() {
        var service = createService("test-service");
        var routeResult = createRouteMatch(service);

        setupLookup(Optional.of(routeResult));

        var source = SourceIdentifier.of("192.168.1.1");
        final var clientContext = new ClientContext("192.168.1.1", false, null);
        when(clientContextResolver.getOrCompute(requestContext, vertxRequest)).thenReturn(clientContext);
        when(accessEvaluator.isAllowed(source, routeResult, service.accessConfig()))
                .thenReturn(false);

        assertThrows(
                HttpProblem.class,
                () -> filter.filter(requestContext, vertxRequest).await().atMost(TIMEOUT));
        verify(securityMonitoring)
                .recordAccessDenied(clientContext, "test-service", "/api/users", "network_policy_denied", 1);
    }

    @Test
    @DisplayName("should skip access checks when no route was resolved")
    void noRouteFound() {
        setupLookup(Optional.empty());

        var result = filter.filter(requestContext, vertxRequest).await().atMost(TIMEOUT);

        assertNull(result);
        verify(accessEvaluator, never()).isAllowed(any(), any(), any());
    }

    @Test
    @DisplayName("should reject a missing route snapshot")
    void missingRouteSnapshot() {
        assertThrows(IllegalStateException.class, () -> filter.filter(requestContext, vertxRequest));
        verify(accessEvaluator, never()).isAllowed(any(), any(), any());
    }

    @Test
    @DisplayName("should reject an invalid route snapshot")
    void invalidRouteSnapshot() {
        when(requestContext.getProperty(RouteContextAttributes.LOOKUP)).thenReturn(Optional.of("invalid"));

        assertThrows(IllegalStateException.class, () -> filter.filter(requestContext, vertxRequest));
        verify(accessEvaluator, never()).isAllowed(any(), any(), any());
    }

    @Test
    @DisplayName("should audit service-wide denials without the requested path")
    void serviceOnlyMatchDenied() {
        var service = createService("my-service");
        var clientContext = new ClientContext("10.0.0.1", false, null);

        setupLookup(Optional.of(createServiceOnlyMatch(service)));
        when(clientContextResolver.getOrCompute(requestContext, vertxRequest)).thenReturn(clientContext);
        when(accessEvaluator.isAllowed(any(), any(ServiceOnlyMatch.class), eq(service.accessConfig())))
                .thenReturn(false);

        assertThrows(
                HttpProblem.class,
                () -> filter.filter(requestContext, vertxRequest).await().atMost(TIMEOUT));
        verify(securityMonitoring).recordAccessDenied(clientContext, "my-service", "/**", "network_policy_denied", 1);
    }
}
