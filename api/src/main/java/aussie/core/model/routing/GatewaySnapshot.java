package aussie.core.model.routing;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import aussie.core.model.common.CorsConfig;
import aussie.core.model.service.ServiceRegistration;

/** Immutable, fully compiled routing state published to request threads as one value. */
public final class GatewaySnapshot {

    private static final GatewaySnapshot EMPTY =
            new GatewaySnapshot(Map.of(), List.of(), RouteIndex.build(List.of()), Map.of());

    private final Map<String, ServiceRoutes> servicesById;
    private final List<ServiceRoutes> services;
    private final RouteIndex routes;
    private final Map<String, CorsConfig> corsByOrigin;

    public static GatewaySnapshot empty() {
        return EMPTY;
    }

    public static GatewaySnapshot build(Collection<ServiceRegistration> registrations) {
        final var routesById = new TreeMap<String, ServiceRoutes>();
        for (final var registration : registrations) {
            routesById.put(registration.serviceId(), ServiceRoutes.of(registration));
        }

        final var routes = List.copyOf(routesById.values());
        rejectConflicts(routes);
        return routes.isEmpty()
                ? EMPTY
                : new GatewaySnapshot(
                        Map.copyOf(routesById),
                        routes,
                        RouteIndex.buildGateway(routesById.values().stream()
                                .map(ServiceRoutes::service)
                                .toList()),
                        indexCors(routes));
    }

    private GatewaySnapshot(
            Map<String, ServiceRoutes> servicesById,
            List<ServiceRoutes> services,
            RouteIndex routes,
            Map<String, CorsConfig> corsByOrigin) {
        this.servicesById = servicesById;
        this.services = services;
        this.routes = routes;
        this.corsByOrigin = corsByOrigin;
    }

    public GatewaySnapshot with(ServiceRegistration registration) {
        final var registrations = services.stream()
                .map(ServiceRoutes::service)
                .filter(service -> !service.serviceId().equals(registration.serviceId()))
                .collect(java.util.stream.Collectors.toList());
        registrations.add(registration);
        return build(registrations);
    }

    public GatewaySnapshot without(String serviceId) {
        return build(services.stream()
                .map(ServiceRoutes::service)
                .filter(service -> !service.serviceId().equals(serviceId))
                .toList());
    }

    public Optional<ServiceRegistration> service(String serviceId) {
        final var routes = servicesById.get(serviceId);
        return routes == null ? Optional.empty() : Optional.of(routes.service());
    }

    public Optional<CorsConfig> corsConfigForOrigin(String origin) {
        final var config = corsByOrigin.getOrDefault(origin, corsByOrigin.get("*"));
        return config != null && config.isOriginAllowed(origin) ? Optional.of(config) : Optional.empty();
    }

    public RouteMatch match(String normalizedPath, String upperMethod) {
        return routes.match(normalizedPath, upperMethod);
    }

    public RouteMatch match(String serviceId, String normalizedPath, String upperMethod) {
        final var serviceRoutes = servicesById.get(serviceId);
        return serviceRoutes == null ? null : serviceRoutes.matchEndpoint(normalizedPath, upperMethod);
    }

    private static Map<String, CorsConfig> indexCors(List<ServiceRoutes> services) {
        final var corsByOrigin = new TreeMap<String, CorsConfig>();
        services.forEach(routes -> routes.service().corsConfig().ifPresent(config -> config.allowedOrigins().stream()
                .filter(origin -> origin != null)
                .forEach(origin -> corsByOrigin.putIfAbsent(origin, config))));
        return Map.copyOf(corsByOrigin);
    }

    private static void rejectConflicts(List<ServiceRoutes> services) {
        for (var leftIndex = 0; leftIndex < services.size(); leftIndex++) {
            final var left = services.get(leftIndex).service();
            for (var rightIndex = leftIndex + 1; rightIndex < services.size(); rightIndex++) {
                final var right = services.get(rightIndex).service();
                for (final var leftEndpoint : left.endpoints()) {
                    for (final var rightEndpoint : right.endpoints()) {
                        if (RouteIndex.overlaps(leftEndpoint, rightEndpoint)) {
                            throw new IllegalArgumentException(
                                    "Route conflict between services %s and %s: %s overlaps %s"
                                            .formatted(
                                                    left.serviceId(),
                                                    right.serviceId(),
                                                    leftEndpoint.path(),
                                                    rightEndpoint.path()));
                        }
                    }
                }
            }
        }
    }
}
