package aussie.system.filter;

import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.in.problem.GatewayProblem;
import aussie.common.context.RouteContextAttributes;
import aussie.core.model.common.SourceIdentifier;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.port.out.SecurityMonitoring;
import aussie.core.service.auth.AccessControlEvaluator;

/**
 * Reactive access control filter for gateway requests.
 *
 * <p>Uses the route lookup computed by {@code RouteResolutionFilter}, so
 * authorization and rate limiting consume the same route snapshot.
 */
public class AccessControlFilter {

    private final ClientContextResolver clientContextResolver;
    private final AccessControlEvaluator accessEvaluator;
    private final SecurityMonitoring securityMonitoring;

    @Inject
    public AccessControlFilter(
            ClientContextResolver clientContextResolver,
            AccessControlEvaluator accessEvaluator,
            SecurityMonitoring securityMonitoring) {
        this.clientContextResolver = clientContextResolver;
        this.accessEvaluator = accessEvaluator;
        this.securityMonitoring = securityMonitoring;
    }

    @ServerRequestFilter
    public Uni<Response> filter(ContainerRequestContext requestContext, HttpServerRequest vertxRequest) {
        final var lookup = requestContext.getProperty(RouteContextAttributes.LOOKUP);
        if (!(lookup instanceof Optional<?> routeLookup)) {
            throw new IllegalStateException("Route lookup is missing from the request context");
        }
        if (routeLookup.isEmpty()) {
            return Uni.createFrom().nullItem();
        }
        if (!(routeLookup.get() instanceof RouteLookupResult route)) {
            throw new IllegalStateException("Route lookup contains an invalid value");
        }
        return checkAccessControl(requestContext, vertxRequest, route);
    }

    private Uni<Response> checkAccessControl(
            ContainerRequestContext requestContext, HttpServerRequest vertxRequest, RouteLookupResult route) {
        final var clientContext = clientContextResolver.getOrCompute(requestContext, vertxRequest);
        var source = SourceIdentifier.of(clientContext.resolvedIp());
        var isAllowed = accessEvaluator.isAllowed(source, route, route.service().accessConfig());

        if (!isAllowed) {
            final var routePattern =
                    route.endpoint().map(endpoint -> endpoint.path()).orElse("/**");
            securityMonitoring.recordAccessDenied(
                    clientContext,
                    route.service().serviceId(),
                    routePattern,
                    "network_policy_denied",
                    route.service().version());
            // Return 404 to hide resource existence from unauthorized users
            throw GatewayProblem.notFound("Not found");
        }

        // Return null to continue processing (no abort)
        return Uni.createFrom().nullItem();
    }
}
