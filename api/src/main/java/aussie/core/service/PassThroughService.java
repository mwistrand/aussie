package aussie.core.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.AussieToken;
import aussie.core.model.EndpointConfig;
import aussie.core.model.GatewayRequest;
import aussie.core.model.GatewayResult;
import aussie.core.model.RouteAuthResult;
import aussie.core.model.RouteMatch;
import aussie.core.model.ServiceRegistration;
import aussie.core.port.in.PassThroughUseCase;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.ProxyClient;
import aussie.core.port.out.SecurityMonitoring;
import aussie.core.port.out.TrafficAttributing;

@ApplicationScoped
public class PassThroughService implements PassThroughUseCase {

    /**
     * Paths that cannot be used as service IDs because they conflict with gateway endpoints:
     * - "admin": Admin API for service registration/management
     * - "gateway": Explicit route-based proxying endpoint
     * - "q": Quarkus built-in endpoints (health, metrics, dev-ui)
     */
    private static final Set<String> RESERVED_PATHS = Set.of("admin", "gateway", "q");

    private final ServiceRegistry serviceRegistry;
    private final ProxyRequestPreparer requestPreparer;
    private final ProxyClient proxyClient;
    private final VisibilityResolver visibilityResolver;
    private final EndpointMatcher endpointMatcher;
    private final RouteAuthenticationService routeAuthService;
    private final Metrics metrics;
    private final SecurityMonitoring securityMonitor;
    private final TrafficAttributing attributionService;

    @Inject
    public PassThroughService(
            ServiceRegistry serviceRegistry,
            ProxyRequestPreparer requestPreparer,
            ProxyClient proxyClient,
            VisibilityResolver visibilityResolver,
            EndpointMatcher endpointMatcher,
            RouteAuthenticationService routeAuthService,
            Metrics metrics,
            SecurityMonitoring securityMonitor,
            TrafficAttributing attributionService) {
        this.serviceRegistry = serviceRegistry;
        this.requestPreparer = requestPreparer;
        this.proxyClient = proxyClient;
        this.visibilityResolver = visibilityResolver;
        this.endpointMatcher = endpointMatcher;
        this.routeAuthService = routeAuthService;
        this.metrics = metrics;
        this.securityMonitor = securityMonitor;
        this.attributionService = attributionService;
    }

    @Override
    public Uni<GatewayResult> forward(String serviceId, GatewayRequest request) {
        long startTime = System.nanoTime();

        if (RESERVED_PATHS.contains(serviceId.toLowerCase())) {
            var result = new GatewayResult.ReservedPath(serviceId);
            metrics.recordGatewayResult(null, result);
            return Uni.createFrom().item(result);
        }

        return serviceRegistry.getService(serviceId).flatMap(serviceOpt -> {
            if (serviceOpt.isEmpty()) {
                var result = new GatewayResult.ServiceNotFound(serviceId);
                metrics.recordGatewayResult(null, result);
                return Uni.createFrom().item(result);
            }

            var service = serviceOpt.get();
            var routeMatch = createRouteMatch(service, request.path(), request.method());

            return routeAuthService
                    .authenticate(request, routeMatch)
                    .flatMap(authResult -> handleAuthResult(authResult, request, routeMatch))
                    .invoke(result -> recordMetrics(request, service, result, startTime));
        });
    }

    private Uni<GatewayResult> handleAuthResult(
            RouteAuthResult authResult, GatewayRequest request, RouteMatch routeMatch) {
        return switch (authResult) {
            case RouteAuthResult.Authenticated auth -> forwardWithToken(request, routeMatch, auth.token());
            case RouteAuthResult.NotRequired notRequired -> forwardWithoutToken(request, routeMatch);
            case RouteAuthResult.Unauthorized unauthorized -> Uni.createFrom()
                    .item(new GatewayResult.Unauthorized(unauthorized.reason()));
            case RouteAuthResult.Forbidden forbidden -> Uni.createFrom()
                    .item(new GatewayResult.Forbidden(forbidden.reason()));
            case RouteAuthResult.BadRequest badRequest -> Uni.createFrom()
                    .item(new GatewayResult.BadRequest(badRequest.reason()));
        };
    }

    private Uni<GatewayResult> forwardWithToken(GatewayRequest request, RouteMatch routeMatch, AussieToken token) {
        Optional<AussieToken> tokenOpt = token.hasToken() ? Optional.of(token) : Optional.empty();
        var preparedRequest = requestPreparer.prepare(request, routeMatch, tokenOpt);

        return proxyClient
                .forward(preparedRequest)
                .map(response -> (GatewayResult) GatewayResult.Success.from(response))
                .onFailure()
                .recoverWithItem(error -> new GatewayResult.Error(error.getMessage()));
    }

    private Uni<GatewayResult> forwardWithoutToken(GatewayRequest request, RouteMatch routeMatch) {
        var preparedRequest = requestPreparer.prepare(request, routeMatch, Optional.empty());

        return proxyClient
                .forward(preparedRequest)
                .map(response -> (GatewayResult) GatewayResult.Success.from(response))
                .onFailure()
                .recoverWithItem(error -> new GatewayResult.Error(error.getMessage()));
    }

    private RouteMatch createRouteMatch(ServiceRegistration service, String targetPath, String method) {
        // First, check if there's a matching endpoint config
        var matchedEndpoint = endpointMatcher.match(targetPath, method, service);

        if (matchedEndpoint.isPresent()) {
            return new RouteMatch(service, matchedEndpoint.get(), targetPath, Map.of());
        }

        // No matching endpoint - create a catch-all with visibility rules and default auth
        var visibility = visibilityResolver.resolve(targetPath, method, service);
        var catchAllEndpoint =
                new EndpointConfig("/**", Set.of("*"), visibility, Optional.empty(), service.defaultAuthRequired());
        return new RouteMatch(service, catchAllEndpoint, targetPath, Map.of());
    }

    private void recordMetrics(
            GatewayRequest request, ServiceRegistration service, GatewayResult result, long startTime) {
        var serviceId = service.serviceId();
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        // Record gateway result
        metrics.recordGatewayResult(serviceId, result);

        // Record request and latency for successful requests
        if (result instanceof GatewayResult.Success success) {
            metrics.recordRequest(serviceId, request.method(), success.statusCode());
            metrics.recordProxyLatency(serviceId, request.method(), success.statusCode(), durationMs);

            // Record traffic attribution
            if (attributionService.isEnabled()) {
                long requestBytes = request.body() != null ? request.body().length : 0;
                long responseBytes = success.body() != null ? success.body().length : 0;
                attributionService.record(request, service, requestBytes, responseBytes, durationMs);
            }
        }

        // Record errors
        if (result instanceof GatewayResult.Error) {
            metrics.recordError(serviceId, "upstream_error");
        }
    }
}
