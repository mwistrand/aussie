package aussie.core.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.GatewayRequest;
import aussie.core.model.GatewayResult;
import aussie.core.port.in.GatewayUseCase;
import aussie.core.port.out.ProxyClient;

@ApplicationScoped
public class GatewayService implements GatewayUseCase {

    private final ServiceRegistry serviceRegistry;
    private final ProxyRequestPreparer requestPreparer;
    private final ProxyClient proxyClient;

    @Inject
    public GatewayService(
            ServiceRegistry serviceRegistry, ProxyRequestPreparer requestPreparer, ProxyClient proxyClient) {
        this.serviceRegistry = serviceRegistry;
        this.requestPreparer = requestPreparer;
        this.proxyClient = proxyClient;
    }

    @Override
    public Uni<GatewayResult> forward(GatewayRequest request) {
        var routeMatch = serviceRegistry.findRoute(request.path(), request.method());

        if (routeMatch.isEmpty()) {
            return Uni.createFrom().item(new GatewayResult.RouteNotFound(request.path()));
        }

        var preparedRequest = requestPreparer.prepare(request, routeMatch.get());

        return proxyClient
                .forward(preparedRequest)
                .map(response -> (GatewayResult) GatewayResult.Success.from(response))
                .onFailure()
                .recoverWithItem(error -> new GatewayResult.Error(error.getMessage()));
    }
}
