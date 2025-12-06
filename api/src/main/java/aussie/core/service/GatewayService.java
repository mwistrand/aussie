package aussie.core.service;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;

import aussie.core.model.AussieToken;
import aussie.core.model.GatewayRequest;
import aussie.core.model.GatewayResult;
import aussie.core.model.RouteAuthResult;
import aussie.core.model.RouteMatch;
import aussie.core.port.in.GatewayUseCase;
import aussie.core.port.out.ProxyClient;

@ApplicationScoped
public class GatewayService implements GatewayUseCase {

    private final ServiceRegistry serviceRegistry;
    private final ProxyRequestPreparer requestPreparer;
    private final ProxyClient proxyClient;
    private final RouteAuthenticationService routeAuthService;

    @Inject
    public GatewayService(
            ServiceRegistry serviceRegistry,
            ProxyRequestPreparer requestPreparer,
            ProxyClient proxyClient,
            RouteAuthenticationService routeAuthService) {
        this.serviceRegistry = serviceRegistry;
        this.requestPreparer = requestPreparer;
        this.proxyClient = proxyClient;
        this.routeAuthService = routeAuthService;
    }

    @Override
    public Uni<GatewayResult> forward(GatewayRequest request) {
        var routeMatch = serviceRegistry.findRoute(request.path(), request.method());

        if (routeMatch.isEmpty()) {
            return Uni.createFrom().item(new GatewayResult.RouteNotFound(request.path()));
        }

        // Check route authentication requirements
        return routeAuthService
                .authenticate(request, routeMatch.get())
                .flatMap(authResult -> handleAuthResult(authResult, request, routeMatch.get()));
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
