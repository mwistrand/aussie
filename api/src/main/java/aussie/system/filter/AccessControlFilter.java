package aussie.system.filter;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.model.EndpointConfig;
import aussie.core.model.RouteMatch;
import aussie.core.service.AccessControlEvaluator;
import aussie.core.service.ServiceRegistry;
import aussie.core.service.SourceIdentifierExtractor;
import aussie.core.service.VisibilityResolver;

/**
 * Reactive access control filter for gateway requests.
 *
 * <p>Uses @ServerRequestFilter with Uni return type to avoid blocking
 * the Vert.x event loop when performing async service lookups.
 */
public class AccessControlFilter {

    private static final Set<String> RESERVED_PATHS = Set.of("admin", "gateway", "q");

    private final ServiceRegistry serviceRegistry;
    private final SourceIdentifierExtractor sourceExtractor;
    private final AccessControlEvaluator accessEvaluator;
    private final VisibilityResolver visibilityResolver;

    @Inject
    public AccessControlFilter(
            ServiceRegistry serviceRegistry,
            SourceIdentifierExtractor sourceExtractor,
            AccessControlEvaluator accessEvaluator,
            VisibilityResolver visibilityResolver) {
        this.serviceRegistry = serviceRegistry;
        this.sourceExtractor = sourceExtractor;
        this.accessEvaluator = accessEvaluator;
        this.visibilityResolver = visibilityResolver;
    }

    @ServerRequestFilter
    public Uni<Response> filter(ContainerRequestContext requestContext) {
        var path = requestContext.getUriInfo().getPath();
        var method = requestContext.getMethod();

        // Normalize path - remove leading slash if present
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        // Handle gateway requests
        if (path.startsWith("gateway/")) {
            return handleGatewayRequest(requestContext, path, method);
        }

        // Handle pass-through requests (/{serviceId}/{path})
        return handlePassThroughRequest(requestContext, path, method);
    }

    private Uni<Response> handleGatewayRequest(ContainerRequestContext requestContext, String path, String method) {
        var gatewayPath = "/" + path.substring("gateway/".length());

        var routeMatch = serviceRegistry.findRoute(gatewayPath, method);
        if (routeMatch.isEmpty()) {
            return Uni.createFrom().nullItem();
        }

        return checkAccessControl(requestContext, routeMatch.get());
    }

    private Uni<Response> handlePassThroughRequest(ContainerRequestContext requestContext, String path, String method) {
        var slashIndex = path.indexOf('/');
        String serviceId;
        String remainingPath;

        if (slashIndex == -1) {
            serviceId = path;
            remainingPath = "/";
        } else {
            serviceId = path.substring(0, slashIndex);
            remainingPath = "/" + path.substring(slashIndex + 1);
        }

        if (RESERVED_PATHS.contains(serviceId.toLowerCase())) {
            return Uni.createFrom().nullItem();
        }

        // Use reactive chain - no blocking!
        final String finalRemainingPath = remainingPath;
        return serviceRegistry.getService(serviceId).flatMap(serviceOpt -> {
            if (serviceOpt.isEmpty()) {
                return Uni.createFrom().nullItem();
            }

            var service = serviceOpt.get();

            // First, try to find a specific route match for this path
            var routeMatch = serviceRegistry.findRoute(finalRemainingPath, method);
            if (routeMatch.isPresent() && routeMatch.get().service().serviceId().equals(serviceId)) {
                return checkAccessControl(requestContext, routeMatch.get());
            }

            // For pass-through, resolve visibility using the VisibilityResolver
            var visibility = visibilityResolver.resolve(finalRemainingPath, method, service);

            var syntheticEndpoint = new EndpointConfig("/**", Set.of("*"), visibility, Optional.empty());
            var syntheticRoute = new RouteMatch(service, syntheticEndpoint, finalRemainingPath, Map.of());

            return checkAccessControl(requestContext, syntheticRoute);
        });
    }

    private Uni<Response> checkAccessControl(ContainerRequestContext requestContext, RouteMatch route) {
        var source = sourceExtractor.extract(requestContext);
        var isAllowed = accessEvaluator.isAllowed(
                source, route.endpoint(), route.service().accessConfig());

        if (!isAllowed) {
            // Return 404 to hide resource existence from unauthorized users
            throw GatewayProblem.notFound("Not found");
        }

        // Return null to continue processing (no abort)
        return Uni.createFrom().nullItem();
    }
}
