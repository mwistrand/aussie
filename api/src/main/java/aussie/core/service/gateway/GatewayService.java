package aussie.core.service.gateway;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.AussieToken;
import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.RouteAuthResult;
import aussie.core.model.routing.RouteMatch;
import aussie.core.port.in.GatewayUseCase;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.ProxyClient;
import aussie.core.port.out.SecurityMonitoring;
import aussie.core.port.out.TrafficAttributing;
import aussie.core.service.routing.ServiceRegistry;

/**
 * Handle gateway proxy requests using configured route matching.
 *
 * <p>Gateway mode provides full route matching capabilities where requests
 * are matched against registered endpoint patterns. This enables:
 * <ul>
 *   <li>Path-based routing with wildcards and path variables</li>
 *   <li>Per-endpoint visibility and authentication settings</li>
 *   <li>Request transformation and path rewriting</li>
 * </ul>
 *
 * <p>The service coordinates authentication, authorization, request preparation,
 * and proxying while recording metrics for observability.
 *
 * <p>All operations are fully reactive and never block.
 */
@ApplicationScoped
public class GatewayService implements GatewayUseCase {

    private final ServiceRegistry serviceRegistry;
    private final ProxyRequestPreparer requestPreparer;
    private final ProxyClient proxyClient;
    private final RouteAuthenticationService routeAuthService;
    private final Metrics metrics;
    private final SecurityMonitoring securityMonitor;
    private final TrafficAttributing attributionService;

    @Inject
    public GatewayService(
            ServiceRegistry serviceRegistry,
            ProxyRequestPreparer requestPreparer,
            ProxyClient proxyClient,
            RouteAuthenticationService routeAuthService,
            Metrics metrics,
            SecurityMonitoring securityMonitor,
            TrafficAttributing attributionService) {
        this.serviceRegistry = serviceRegistry;
        this.requestPreparer = requestPreparer;
        this.proxyClient = proxyClient;
        this.routeAuthService = routeAuthService;
        this.metrics = metrics;
        this.securityMonitor = securityMonitor;
        this.attributionService = attributionService;
    }

    @Override
    public Uni<GatewayResult> forward(GatewayRequest request) {
        final long startTime = System.nanoTime();

        // Use async route lookup to ensure cache freshness in multi-instance deployments
        return serviceRegistry.findRouteAsync(request.path(), request.method()).flatMap(routeResult -> {
            if (routeResult.isEmpty()) {
                var result = new GatewayResult.RouteNotFound(request.path());
                metrics.recordGatewayResult(null, result);
                return Uni.createFrom().item(result);
            }

            // Gateway requires a RouteMatch (with endpoint) to forward requests
            if (!(routeResult.get() instanceof RouteMatch routeMatch)) {
                var result = new GatewayResult.RouteNotFound(request.path());
                metrics.recordGatewayResult(null, result);
                return Uni.createFrom().item(result);
            }

            var service = routeMatch.service();

            // Check route authentication requirements
            return routeAuthService
                    .authenticate(request, routeMatch)
                    .flatMap(authResult -> handleAuthResult(authResult, request, routeMatch))
                    .invoke(result -> recordMetrics(request, service, result, startTime));
        });
    }

    private void recordMetrics(
            GatewayRequest request,
            aussie.core.model.service.ServiceRegistration service,
            GatewayResult result,
            long startTime) {
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
        if (result instanceof GatewayResult.Error error) {
            metrics.recordError(serviceId, "upstream_error");
        }
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
}
