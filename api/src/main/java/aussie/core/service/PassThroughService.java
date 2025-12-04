package aussie.core.service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.EndpointConfig;
import aussie.core.model.EndpointVisibility;
import aussie.core.model.GatewayRequest;
import aussie.core.model.GatewayResult;
import aussie.core.model.RouteMatch;
import aussie.core.model.ServiceRegistration;
import aussie.core.port.in.PassThroughUseCase;
import aussie.core.port.out.ProxyClient;

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

    @Inject
    public PassThroughService(
            ServiceRegistry serviceRegistry, ProxyRequestPreparer requestPreparer, ProxyClient proxyClient) {
        this.serviceRegistry = serviceRegistry;
        this.requestPreparer = requestPreparer;
        this.proxyClient = proxyClient;
    }

    @Override
    public Uni<GatewayResult> forward(String serviceId, GatewayRequest request) {
        if (RESERVED_PATHS.contains(serviceId.toLowerCase())) {
            return Uni.createFrom().item(new GatewayResult.ReservedPath(serviceId));
        }

        var serviceOpt = serviceRegistry.getService(serviceId);
        if (serviceOpt.isEmpty()) {
            return Uni.createFrom().item(new GatewayResult.ServiceNotFound(serviceId));
        }

        var service = serviceOpt.get();
        var routeMatch = createPassThroughRouteMatch(service, request.path());
        var preparedRequest = requestPreparer.prepare(request, routeMatch);

        return proxyClient
                .forward(preparedRequest)
                .map(response -> (GatewayResult) GatewayResult.Success.from(response))
                .onFailure()
                .recoverWithItem(error -> new GatewayResult.Error(error.getMessage()));
    }

    private RouteMatch createPassThroughRouteMatch(ServiceRegistration service, String targetPath) {
        var catchAllEndpoint = new EndpointConfig("/**", Set.of("*"), EndpointVisibility.PUBLIC, Optional.empty());
        return new RouteMatch(service, catchAllEndpoint, targetPath, Map.of());
    }
}
