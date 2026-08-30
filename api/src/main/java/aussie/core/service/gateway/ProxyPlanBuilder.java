package aussie.core.service.gateway;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.ProxyPlan;
import aussie.core.model.gateway.RouteAuthResult;
import aussie.core.model.routing.RouteMatch;

@ApplicationScoped
public class ProxyPlanBuilder {

    private final ProxyRequestPreparer requestPreparer;

    @Inject
    public ProxyPlanBuilder(ProxyRequestPreparer requestPreparer) {
        this.requestPreparer = requestPreparer;
    }

    public ProxyPlan build(GatewayRequest request, RouteMatch routeMatch, RouteAuthResult authResult) {
        return switch (authResult) {
            case RouteAuthResult.Authenticated auth ->
                new ProxyPlan.Ready(
                        request,
                        requestPreparer.prepare(request, routeMatch, Optional.of(auth.token())),
                        routeMatch.service());
            case RouteAuthResult.NotRequired ignored ->
                new ProxyPlan.Ready(request, requestPreparer.prepare(request, routeMatch), routeMatch.service());
            case RouteAuthResult.Unauthorized unauthorized ->
                new ProxyPlan.Rejected(
                        new GatewayResult.Unauthorized(unauthorized.reason()),
                        routeMatch.service().serviceId());
            case RouteAuthResult.Forbidden forbidden ->
                new ProxyPlan.Rejected(
                        new GatewayResult.Forbidden(forbidden.reason()),
                        routeMatch.service().serviceId());
            case RouteAuthResult.BadRequest badRequest ->
                new ProxyPlan.Rejected(
                        new GatewayResult.BadRequest(badRequest.reason()),
                        routeMatch.service().serviceId());
        };
    }
}
