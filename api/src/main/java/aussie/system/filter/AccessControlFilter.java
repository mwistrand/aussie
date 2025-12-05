package aussie.system.filter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import org.jboss.logging.Logger;

import aussie.core.model.EndpointConfig;
import aussie.core.model.RouteMatch;
import aussie.core.model.ServiceRegistration;
import aussie.core.service.AccessControlEvaluator;
import aussie.core.service.ServiceRegistry;
import aussie.core.service.SourceIdentifierExtractor;
import aussie.core.service.VisibilityResolver;

@Provider
@Priority(Priorities.AUTHORIZATION)
public class AccessControlFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(AccessControlFilter.class);
    private static final Set<String> RESERVED_PATHS = Set.of("admin", "gateway", "q");
    private static final long LOOKUP_TIMEOUT_SECONDS = 5;

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

        // Get service asynchronously and wait for result
        Optional<ServiceRegistration> serviceOpt;
        try {
            serviceOpt = serviceRegistry
                    .getService(serviceId)
                    .subscribeAsCompletionStage()
                    .get(LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOG.warnf("Failed to lookup service %s: %s", serviceId, e.getMessage());
            return;
        }

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

        // For pass-through, resolve visibility using the VisibilityResolver
        var visibility = visibilityResolver.resolve(remainingPath, method, service);

        var syntheticEndpoint = new EndpointConfig("/**", Set.of("*"), visibility, Optional.empty());
        var syntheticRoute = new RouteMatch(service, syntheticEndpoint, remainingPath, Map.of());

        checkAccessControl(requestContext, syntheticRoute);
    }

    private void checkAccessControl(ContainerRequestContext requestContext, RouteMatch route) {
        var source = sourceExtractor.extract(requestContext);
        var isPublic = accessEvaluator.isAllowed(
                source, route.endpoint(), route.service().accessConfig());

        if (!isPublic) {
            requestContext.abortWith(Response.status(Response.Status.NOT_FOUND)
                    .entity("Not found")
                    .build());
        }
    }
}
