package aussie.core.port.in;

import io.smallrye.mutiny.Uni;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.ProxyPlan;

/**
 * Use case for preparing gateway requests using route matching.
 *
 * <p>Gateway mode matches requests against configured endpoint patterns
 * and produces a plan for the streaming HTTP exchange.
 */
public interface GatewayUseCase {

    Uni<ProxyPlan> prepare(GatewayRequest request);
}
