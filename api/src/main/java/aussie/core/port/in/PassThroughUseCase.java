package aussie.core.port.in;

import io.smallrye.mutiny.Uni;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.ProxyPlan;

/**
 * Use case for preparing requests to a service by ID.
 *
 * <p>Pass-through mode resolves requests using the service ID from the URL path
 * and produces a plan for the streaming HTTP exchange.
 */
public interface PassThroughUseCase {

    Uni<ProxyPlan> prepare(String serviceId, GatewayRequest request);
}
