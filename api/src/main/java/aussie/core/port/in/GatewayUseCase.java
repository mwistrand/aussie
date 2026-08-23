package aussie.core.port.in;

import io.smallrye.mutiny.Uni;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.ProxyPlan;

/**
 * Use case for forwarding requests through the gateway using route matching.
 *
 * <p>Gateway mode matches requests against configured endpoint patterns
 * and forwards them to the appropriate backend service.
 */
public interface GatewayUseCase {

    Uni<ProxyPlan> prepare(GatewayRequest request);

    /**
     * Forward a request through the gateway.
     *
     * @param request the gateway request containing path, method, headers, and body
     * @return the gateway result indicating success, authentication failure, or error
     */
    Uni<GatewayResult> forward(GatewayRequest request);
}
