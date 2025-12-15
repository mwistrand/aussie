package aussie.core.port.out;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.service.ServiceRegistration;

/**
 * Port interface for traffic attribution and cost allocation.
 *
 * <p>Implementations track traffic metrics for billing and reporting purposes,
 * attributing requests to teams, tenants, and cost centers.
 */
public interface TrafficAttributing {

    /**
     * Check if traffic attribution is enabled.
     *
     * @return true if enabled
     */
    boolean isEnabled();

    /**
     * Record attributed traffic for a completed request.
     *
     * @param request the gateway request
     * @param service the target service
     * @param requestBodySize request body size in bytes
     * @param responseBodySize response body size in bytes
     * @param durationMs request duration in milliseconds
     */
    void record(
            GatewayRequest request,
            ServiceRegistration service,
            long requestBodySize,
            long responseBodySize,
            long durationMs);
}
