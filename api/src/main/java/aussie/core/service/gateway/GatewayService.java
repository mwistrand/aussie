package aussie.core.service.gateway;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.ProxyPlan;
import aussie.core.model.gateway.RouteAuthResult;
import aussie.core.model.routing.RouteMatch;
import aussie.core.port.in.GatewayUseCase;
import aussie.core.port.out.AuthenticatedContext;
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
    private final AuthenticatedContext authenticatedContext;

    @Inject
    public GatewayService(
            ServiceRegistry serviceRegistry,
            ProxyRequestPreparer requestPreparer,
            ProxyClient proxyClient,
            RouteAuthenticationService routeAuthService,
            Metrics metrics,
            SecurityMonitoring securityMonitor,
            TrafficAttributing attributionService,
            AuthenticatedContext authenticatedContext) {
        this.serviceRegistry = serviceRegistry;
        this.requestPreparer = requestPreparer;
        this.proxyClient = proxyClient;
        this.routeAuthService = routeAuthService;
        this.metrics = metrics;
        this.securityMonitor = securityMonitor;
        this.attributionService = attributionService;
        this.authenticatedContext = authenticatedContext;
    }

    @Override
    public Uni<GatewayResult> forward(GatewayRequest request) {
        final long startTime = System.nanoTime();

        return prepare(request)
                .flatMap(plan -> execute(plan).invoke(result -> recordMetrics(request, plan, result, startTime)));
    }

    @Override
    public Uni<ProxyPlan> prepare(GatewayRequest request) {

        // Use async route lookup to ensure cache freshness in multi-instance deployments
        return serviceRegistry.findRouteAsync(request.path(), request.method()).flatMap(routeResult -> {
            if (routeResult.isEmpty()) {
                var result = new GatewayResult.RouteNotFound(request.path());
                return Uni.createFrom().item(new ProxyPlan.Rejected(result, null));
            }

            // Gateway requires a RouteMatch (with endpoint) to forward requests
            if (!(routeResult.get() instanceof RouteMatch routeMatch)) {
                var result = new GatewayResult.RouteNotFound(request.path());
                return Uni.createFrom().item(new ProxyPlan.Rejected(result, null));
            }

            // Check route authentication requirements
            return routeAuthService
                    .authenticate(request, routeMatch)
                    .map(authResult -> handleAuthResult(authResult, request, routeMatch));
        });
    }

    private void recordMetrics(GatewayRequest request, ProxyPlan plan, GatewayResult result, long startTime) {
        final var serviceId = plan.serviceId();
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;

        // Record gateway result
        metrics.recordGatewayResult(serviceId, result);

        // Record request and latency for successful requests
        if (result instanceof GatewayResult.Success success) {
            metrics.recordRequest(serviceId, request.method(), success.statusCode());
            metrics.recordProxyLatency(serviceId, request.method(), success.statusCode(), durationMs);

            // Record traffic attribution using authenticated team ID
            if (attributionService.isEnabled() && plan instanceof ProxyPlan.Ready ready) {
                long requestBytes = request.body() != null ? request.body().length : 0;
                long responseBytes = success.body() != null ? success.body().length : 0;
                attributionService.record(
                        request,
                        ready.service(),
                        authenticatedContext.getTeamId(),
                        requestBytes,
                        responseBytes,
                        durationMs);
            }
        }

        // Record errors
        if (result instanceof GatewayResult.Error error) {
            metrics.recordError(serviceId, "upstream_error");
        }
    }

    private ProxyPlan handleAuthResult(RouteAuthResult authResult, GatewayRequest request, RouteMatch routeMatch) {
        return switch (authResult) {
            case RouteAuthResult.Authenticated auth -> new ProxyPlan.Ready(
                    request,
                    requestPreparer.prepare(
                            request,
                            routeMatch,
                            auth.token().hasToken() ? Optional.of(auth.token()) : Optional.empty()),
                    routeMatch.service());
            case RouteAuthResult.NotRequired notRequired -> new ProxyPlan.Ready(
                    request, requestPreparer.prepare(request, routeMatch, Optional.empty()), routeMatch.service());
            case RouteAuthResult.Unauthorized unauthorized -> new ProxyPlan.Rejected(
                    new GatewayResult.Unauthorized(unauthorized.reason()),
                    routeMatch.service().serviceId());
            case RouteAuthResult.Forbidden forbidden -> new ProxyPlan.Rejected(
                    new GatewayResult.Forbidden(forbidden.reason()),
                    routeMatch.service().serviceId());
            case RouteAuthResult.BadRequest badRequest -> new ProxyPlan.Rejected(
                    new GatewayResult.BadRequest(badRequest.reason()),
                    routeMatch.service().serviceId());
        };
    }

    private Uni<GatewayResult> execute(ProxyPlan plan) {
        if (plan instanceof ProxyPlan.Rejected rejected) {
            return Uni.createFrom().item(rejected.result());
        }
        return proxyClient
                .forward(((ProxyPlan.Ready) plan).request())
                .map(response -> (GatewayResult) GatewayResult.Success.from(response))
                .onFailure()
                .recoverWithItem(error -> new GatewayResult.Error("Upstream request failed"));
    }
}
