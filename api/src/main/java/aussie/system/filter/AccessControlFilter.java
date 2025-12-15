package aussie.system.filter;

import java.util.Set;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;

import io.smallrye.mutiny.Uni;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.model.routing.ServiceOnlyMatch;
import aussie.core.model.service.ServicePath;
import aussie.core.service.auth.AccessControlEvaluator;
import aussie.core.service.common.SourceIdentifierExtractor;
import aussie.core.service.routing.ServiceRegistry;

/**
 * Reactive access control filter for gateway requests.
 *
 * <p>
 * Uses @ServerRequestFilter with Uni return type to avoid blocking
 * the Vert.x event loop when performing async service lookups.
 */
public class AccessControlFilter {

    private static final Set<String> RESERVED_PATHS = Set.of("admin", "gateway", "q");

    private final ServiceRegistry serviceRegistry;
    private final SourceIdentifierExtractor sourceExtractor;
    private final AccessControlEvaluator accessEvaluator;

    @Inject
    public AccessControlFilter(
            ServiceRegistry serviceRegistry,
            SourceIdentifierExtractor sourceExtractor,
            AccessControlEvaluator accessEvaluator) {
        this.serviceRegistry = serviceRegistry;
        this.sourceExtractor = sourceExtractor;
        this.accessEvaluator = accessEvaluator;
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

        var routeResult = serviceRegistry.findRoute(gatewayPath, method);
        if (routeResult.isEmpty()) {
            return Uni.createFrom().nullItem();
        }

        return checkAccessControl(requestContext, routeResult.get());
    }

    private Uni<Response> handlePassThroughRequest(ContainerRequestContext requestContext, String path, String method) {
        final var servicePath = ServicePath.parse(path);

        if (RESERVED_PATHS.contains(servicePath.serviceId().toLowerCase())) {
            return Uni.createFrom().nullItem();
        }

        // Use reactive chain - no blocking!
        return serviceRegistry.getService(servicePath.serviceId()).flatMap(serviceOpt -> {
            if (serviceOpt.isEmpty()) {
                return Uni.createFrom().nullItem();
            }

            var service = serviceOpt.get();

            // First, try to find a specific route match for this path
            var routeResult = serviceRegistry.findRoute(servicePath.path(), method);
            if (routeResult.isPresent()
                    && routeResult.get().service().serviceId().equals(servicePath.serviceId())) {
                return checkAccessControl(requestContext, routeResult.get());
            }

            // For pass-through without a specific route, use ServiceOnlyMatch
            // which uses service defaults for visibility/authRequired/rateLimitConfig
            return checkAccessControl(requestContext, new ServiceOnlyMatch(service));
        });
    }

    private Uni<Response> checkAccessControl(ContainerRequestContext requestContext, RouteLookupResult route) {
        var source = sourceExtractor.extract(requestContext);
        var isAllowed = accessEvaluator.isAllowed(source, route, route.service().accessConfig());

        if (!isAllowed) {
            // Return 404 to hide resource existence from unauthorized users
            throw GatewayProblem.notFound("Not found");
        }

        // Return null to continue processing (no abort)
        return Uni.createFrom().nullItem();
    }
}
