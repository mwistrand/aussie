package aussie.core.model.service;

/**
 * Represents a parsed service path with service ID and endpoint path components.
 *
 * <p>
 * Gateway paths follow the format {@code /{serviceId}/{path}}, where the service ID
 * is the first segment and the remaining path is the endpoint within that service.
 *
 * @param serviceId the service identifier (first path segment)
 * @param path the endpoint path within the service
 */
public record ServicePath(String serviceId, String path) {

    /**
     * Default values for unknown or empty paths.
     */
    public static final String UNKNOWN_SERVICE = "unknown";

    public static final String ROOT_PATH = "/";

    public ServicePath {
        if (serviceId == null || serviceId.isEmpty()) {
            serviceId = UNKNOWN_SERVICE;
        }
        if (path == null || path.isEmpty()) {
            path = ROOT_PATH;
        }
    }

    /**
     * Parse a full request path into service ID and endpoint path components.
     *
     * <p>
     * Examples:
     * <ul>
     * <li>{@code /demo-service/api/users} → serviceId=demo-service, path=/api/users</li>
     * <li>{@code demo-service/api/users} → serviceId=demo-service, path=/api/users</li>
     * <li>{@code /demo-service} → serviceId=demo-service, path=/</li>
     * <li>{@code /demo-service/} → serviceId=demo-service, path=/</li>
     * <li>{@code null} → serviceId=unknown, path=/</li>
     * <li>{@code ""} → serviceId=unknown, path=/</li>
     * <li>{@code /} → serviceId=unknown, path=/</li>
     * </ul>
     *
     * @param fullPath the full request path to parse
     * @return a ServicePath with extracted components
     */
    public static ServicePath parse(String fullPath) {
        if (fullPath == null || fullPath.isEmpty() || ROOT_PATH.equals(fullPath)) {
            return new ServicePath(UNKNOWN_SERVICE, ROOT_PATH);
        }

        // Normalize path - remove leading slash if present
        final var normalizedPath = fullPath.startsWith("/") ? fullPath.substring(1) : fullPath;

        if (normalizedPath.isEmpty()) {
            return new ServicePath(UNKNOWN_SERVICE, ROOT_PATH);
        }

        final var slashIndex = normalizedPath.indexOf('/');

        if (slashIndex == -1) {
            // No slash found - entire path is the service ID
            return new ServicePath(normalizedPath, ROOT_PATH);
        }

        final var serviceId = normalizedPath.substring(0, slashIndex);
        final var remainingPath = normalizedPath.substring(slashIndex);

        // Handle trailing slash case (e.g., /demo-service/)
        if (remainingPath.equals("/")) {
            return new ServicePath(serviceId, ROOT_PATH);
        }

        return new ServicePath(serviceId, remainingPath);
    }
}
