package aussie.core.port.in;

import io.smallrye.mutiny.Uni;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;

/**
 * Use case for forwarding requests directly to a service by ID.
 *
 * <p>Pass-through mode routes requests using the service ID from the URL path
 * without complex route pattern matching.
 */
public interface PassThroughUseCase {

    /**
     * Forward a request to the specified service.
     *
     * @param serviceId the target service identifier
     * @param request   the gateway request containing path, method, headers, and body
     * @return the gateway result indicating success, authentication failure, or error
     */
    Uni<GatewayResult> forward(String serviceId, GatewayRequest request);
}
