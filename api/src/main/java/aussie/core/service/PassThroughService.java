package aussie.core.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.config.TelemetryConfig;
import aussie.core.model.AussieToken;
import aussie.core.model.EndpointConfig;
import aussie.core.model.GatewayRequest;
import aussie.core.model.GatewayResult;
import aussie.core.model.RouteAuthResult;
import aussie.core.model.RouteMatch;
import aussie.core.model.ServiceRegistration;
import aussie.core.port.in.PassThroughUseCase;
import aussie.core.port.out.ProxyClient;
import aussie.telemetry.attribution.RequestMetrics;
import aussie.telemetry.attribution.TrafficAttributionService;
import aussie.telemetry.metrics.GatewayMetrics;
import aussie.telemetry.security.SecurityMonitor;

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
    private final GatewayMetrics metrics;
    private final SecurityMonitor securityMonitor;
    private final TrafficAttributionService attributionService;
    private final TelemetryConfig telemetryConfig;

    @Inject
    public PassThroughService(
            ServiceRegistry serviceRegistry,
            ProxyRequestPreparer requestPreparer,
            ProxyClient proxyClient,
            VisibilityResolver visibilityResolver,
            EndpointMatcher endpointMatcher,
            RouteAuthenticationService routeAuthService,
            GatewayMetrics metrics,
            SecurityMonitor securityMonitor,
            TrafficAttributionService attributionService,
            TelemetryConfig telemetryConfig) {
        this.serviceRegistry = serviceRegistry;
        this.requestPreparer = requestPreparer;
        this.proxyClient = proxyClient;
        this.visibilityResolver = visibilityResolver;
        this.endpointMatcher = endpointMatcher;
        this.routeAuthService = routeAuthService;
        this.metrics = metrics;
        this.securityMonitor = securityMonitor;
        this.attributionService = attributionService;
        this.telemetryConfig = telemetryConfig;
    }

    @Override
    public Uni<GatewayResult> forward(String serviceId, GatewayRequest request) {
        long startTime = System.nanoTime();

        if (RESERVED_PATHS.contains(serviceId.toLowerCase())) {
            return Uni.createFrom().item(new GatewayResult.ReservedPath(serviceId));
        }

        return serviceRegistry.getService(serviceId).flatMap(serviceOpt -> {
            if (serviceOpt.isEmpty()) {
                metrics.recordServiceNotFound(serviceId);
                return Uni.createFrom().item(new GatewayResult.ServiceNotFound(serviceId));
            }

            var service = serviceOpt.get();
            var routeMatch = createRouteMatch(service, request.path(), request.method());

            return routeAuthService
                    .authenticate(request, routeMatch)
                    .flatMap(authResult -> handleAuthResult(authResult, request, routeMatch))
                    .invoke(result -> recordTelemetry(serviceId, request, result, startTime));
        });
    }

    private void recordTelemetry(String serviceId, GatewayRequest request, GatewayResult result, long startTime) {
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        int statusCode = getStatusCode(result);
        boolean isError = statusCode >= 400;
        long requestBytes = request.body() != null ? request.body().length : 0;
        long responseBytes = getResponseBytes(result);

        // Record request metrics
        metrics.recordRequest(serviceId, request.method(), statusCode, durationMs);
        metrics.recordRequestSize(serviceId, requestBytes, responseBytes);

        // Record security monitoring
        String clientIp = extractClientIp(request);
        securityMonitor.recordRequest(clientIp, serviceId, isError);

        // Record traffic attribution
        if (telemetryConfig.trafficAttribution().enabled()) {
            var attribution = attributionService.extractAttribution(
                    serviceId,
                    getHeader(request, attributionService.getTeamHeaderName()),
                    getHeader(request, attributionService.getCostCenterHeaderName()),
                    getHeader(request, attributionService.getTenantHeaderName()),
                    getHeader(request, "X-Client-Application"));

            var requestMetrics = RequestMetrics.of(requestBytes, responseBytes, durationMs, statusCode);
            attributionService.recordAttributedRequest(attribution, requestMetrics);
        }

        // Record specific error types
        if (result instanceof GatewayResult.Error) {
            metrics.recordError(serviceId, "proxy_error");
        } else if (result instanceof GatewayResult.Unauthorized) {
            metrics.recordError(serviceId, "unauthorized");
        } else if (result instanceof GatewayResult.Forbidden) {
            metrics.recordError(serviceId, "forbidden");
        }
    }

    private int getStatusCode(GatewayResult result) {
        return switch (result) {
            case GatewayResult.Success success -> success.statusCode();
            case GatewayResult.RouteNotFound ignored -> 404;
            case GatewayResult.ServiceNotFound ignored -> 404;
            case GatewayResult.ReservedPath ignored -> 400;
            case GatewayResult.Unauthorized ignored -> 401;
            case GatewayResult.Forbidden ignored -> 403;
            case GatewayResult.BadRequest ignored -> 400;
            case GatewayResult.Error ignored -> 502;
        };
    }

    private long getResponseBytes(GatewayResult result) {
        if (result instanceof GatewayResult.Success success && success.body() != null) {
            return success.body().length;
        }
        return 0;
    }

    private String extractClientIp(GatewayRequest request) {
        var forwardedFor = getHeader(request, "X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        var realIp = getHeader(request, "X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return "unknown";
    }

    private String getHeader(GatewayRequest request, String headerName) {
        var values = request.headers().get(headerName);
        if (values != null && !values.isEmpty()) {
            return values.get(0);
        }
        for (var entry : request.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(headerName) && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
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
}
