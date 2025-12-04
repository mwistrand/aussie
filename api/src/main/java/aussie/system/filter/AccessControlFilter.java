package aussie.system.filter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import aussie.core.model.EndpointConfig;
import aussie.core.model.EndpointVisibility;
import aussie.core.model.RouteMatch;
import aussie.core.service.AccessControlEvaluator;
import aussie.core.service.ServiceRegistry;
import aussie.core.service.SourceIdentifierExtractor;

@Provider
@Priority(Priorities.AUTHORIZATION)
public class AccessControlFilter implements ContainerRequestFilter {

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

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        var path = requestContext.getUriInfo().getPath();
        var method = requestContext.getMethod();

        // Normalize path - remove leading slash if present
        if (path.startsWith("/")) {
            path = path.substring(1);
        }

        // Handle gateway requests
        if (path.startsWith("gateway/")) {
            handleGatewayRequest(requestContext, path, method);
            return;
        }

        // Handle pass-through requests (/{serviceId}/{path})
        handlePassThroughRequest(requestContext, path, method);
    }

    private void handleGatewayRequest(ContainerRequestContext requestContext, String path, String method) {
        var gatewayPath = "/" + path.substring("gateway/".length());

        var routeMatch = serviceRegistry.findRoute(gatewayPath, method);
        if (routeMatch.isEmpty()) {
            return;
        }

        checkAccessControl(requestContext, routeMatch.get());
    }

    private void handlePassThroughRequest(ContainerRequestContext requestContext, String path, String method) {
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
            return;
        }

        var serviceOpt = serviceRegistry.getService(serviceId);
        if (serviceOpt.isEmpty()) {
            return;
        }

        var service = serviceOpt.get();

        // First, try to find a specific route match for this path
        var routeMatch = serviceRegistry.findRoute(remainingPath, method);
        if (routeMatch.isPresent() && routeMatch.get().service().serviceId().equals(serviceId)) {
            checkAccessControl(requestContext, routeMatch.get());
            return;
        }

        // For pass-through, create a synthetic route match
        // Use service's default visibility (PUBLIC if no specific access config)
        var visibility = service.accessConfig().isPresent() ? EndpointVisibility.PRIVATE : EndpointVisibility.PUBLIC;

        var syntheticEndpoint = new EndpointConfig("/**", Set.of("*"), visibility, Optional.empty());
        var syntheticRoute = new RouteMatch(service, syntheticEndpoint, remainingPath, Map.of());

        checkAccessControl(requestContext, syntheticRoute);
    }

    private void checkAccessControl(ContainerRequestContext requestContext, RouteMatch route) {
        var source = sourceExtractor.extract(requestContext);
        var isAllowed = accessEvaluator.isAllowed(
                source, route.endpoint(), route.service().accessConfig());

        if (!isAllowed) {
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .entity("Access denied: source not authorized for private endpoint")
                    .build());
        }
    }
}
