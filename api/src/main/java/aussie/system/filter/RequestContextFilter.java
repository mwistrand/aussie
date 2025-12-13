package aussie.system.filter;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

import aussie.core.model.RouteMatch;
import aussie.core.service.ServiceRegistry;

/**
 * Filter that extracts and sets request context properties early in the request
 * lifecycle.
 *
 * <p>
 * This filter runs before all other filters to ensure context properties are
 * available
 * to rate limiting, authentication, metrics, and other components.
 *
 * <p>
 * Uses {@link ServiceRegistry#findRoute(String, String)} for route matching.
 * When a route
 * is found, the {@link RouteMatch} is stored in the context for downstream use.
 *
 * <p>
 * Sets the following context properties:
 * <ul>
 * <li>{@code aussie.routeMatch} - The matched route (null if no match)</li>
 * <li>{@code aussie.serviceId} - The service ID from the matched route or
 * path</li>
 * <li>{@code aussie.endpointPath} - The endpoint path from the matched route or
 * path</li>
 * </ul>
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 200)
public class RequestContextFilter implements ContainerRequestFilter {

    public static final String SERVICE_ID_PROPERTY = "aussie.serviceId";
    public static final String ENDPOINT_PATH_PROPERTY = "aussie.endpointPath";
    public static final String ROUTE_MATCH_PROPERTY = "aussie.routeMatch";

    private final ServiceRegistry serviceRegistry;

    @Inject
    public RequestContextFilter(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        final var path = requestContext.getUriInfo().getPath();
        final var method = requestContext.getMethod();
        final var routeMatch = serviceRegistry.findRoute(path, method);

        if (routeMatch.isPresent()) {
            final var match = routeMatch.get();
            requestContext.setProperty(ROUTE_MATCH_PROPERTY, match);
            requestContext.setProperty(SERVICE_ID_PROPERTY, match.service().serviceId());
            requestContext.setProperty(ENDPOINT_PATH_PROPERTY, match.endpoint().path());
        } else {
            // Fallback for gateway paths and unregistered services
            requestContext.setProperty(SERVICE_ID_PROPERTY, extractFirstSegment(path));
            requestContext.setProperty(ENDPOINT_PATH_PROPERTY, extractRemainder(path));
        }
    }

    /**
     * Extracts the first path segment (service ID) from the request path.
     *
     * <p>
     * For paths like {@code /demo-service/api/users}, returns {@code demo-service}.
     *
     * @param path the request path
     * @return the first segment, or "unknown" if not determinable
     */
    private String extractFirstSegment(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "unknown";
        }

        final var normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        final var slashIndex = normalizedPath.indexOf('/');
        final var firstSegment = slashIndex > 0 ? normalizedPath.substring(0, slashIndex) : normalizedPath;

        return firstSegment.isEmpty() ? "unknown" : firstSegment;
    }

    /**
     * Extracts the remainder of the path after the first segment.
     *
     * <p>
     * For paths like {@code /demo-service/api/users}, returns {@code /api/users}.
     *
     * @param path the request path
     * @return the remainder, or "/" if at root
     */
    private String extractRemainder(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }

        final var normalizedPath = path.startsWith("/") ? path.substring(1) : path;
        final var slashIndex = normalizedPath.indexOf('/');

        if (slashIndex < 0 || slashIndex == normalizedPath.length() - 1) {
            return "/";
        }

        return "/" + normalizedPath.substring(slashIndex + 1);
    }
}
