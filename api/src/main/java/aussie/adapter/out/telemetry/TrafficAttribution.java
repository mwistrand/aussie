package aussie.adapter.out.telemetry;

import aussie.config.TelemetryConfigMapping;
import aussie.core.model.GatewayRequest;
import aussie.core.model.ServiceRegistration;

/**
 * Traffic attribution dimensions for cost allocation.
 *
 * <p>This record captures all the dimensions used to attribute traffic
 * to teams, tenants, and cost centers for billing and reporting purposes.
 *
 * @param serviceId the target service identifier
 * @param teamId the team owning or consuming the service
 * @param costCenter the billing cost center
 * @param tenantId the tenant identifier (for multi-tenant deployments)
 * @param clientApplication the client application making the request
 * @param environment the deployment environment (dev/staging/prod)
 */
public record TrafficAttribution(
        String serviceId,
        String teamId,
        String costCenter,
        String tenantId,
        String clientApplication,
        String environment) {

    /**
     * Extract attribution dimensions from a request and service registration.
     *
     * @param request the gateway request
     * @param service the target service registration
     * @param config the telemetry configuration
     * @return traffic attribution dimensions
     */
    public static TrafficAttribution from(
            GatewayRequest request, ServiceRegistration service, TelemetryConfigMapping.AttributionConfig config) {

        return new TrafficAttribution(
                service.serviceId(),
                getHeader(request, config.teamHeader()),
                null, // costCenter - would come from service metadata if available
                getHeader(request, config.tenantHeader()),
                getHeader(request, config.clientAppHeader()),
                System.getenv("AUSSIE_ENV"));
    }

    /**
     * Extract attribution from request headers only (when service not yet resolved).
     *
     * @param request the gateway request
     * @param config the telemetry configuration
     * @return traffic attribution dimensions
     */
    public static TrafficAttribution fromRequest(
            GatewayRequest request, TelemetryConfigMapping.AttributionConfig config) {
        return new TrafficAttribution(
                null,
                getHeader(request, config.teamHeader()),
                null,
                getHeader(request, config.tenantHeader()),
                getHeader(request, config.clientAppHeader()),
                System.getenv("AUSSIE_ENV"));
    }

    private static String getHeader(GatewayRequest request, String headerName) {
        if (request.headers() == null) {
            return null;
        }
        var values = request.headers().get(headerName);
        return values != null && !values.isEmpty() ? values.get(0) : null;
    }

    /**
     * Returns the service ID or "unknown" if null.
     *
     * @return service ID
     */
    public String serviceIdOrUnknown() {
        return serviceId != null ? serviceId : "unknown";
    }

    /**
     * Returns the team ID or "unknown" if null.
     *
     * @return team ID
     */
    public String teamIdOrUnknown() {
        return teamId != null ? teamId : "unknown";
    }

    /**
     * Returns the tenant ID or "unknown" if null.
     *
     * @return tenant ID
     */
    public String tenantIdOrUnknown() {
        return tenantId != null ? tenantId : "unknown";
    }

    /**
     * Returns the environment or "unknown" if null.
     *
     * @return environment
     */
    public String environmentOrUnknown() {
        return environment != null ? environment : "unknown";
    }
}
