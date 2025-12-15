package aussie.core.port.in;

import io.smallrye.mutiny.Uni;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;

public interface PassThroughUseCase {

    Uni<GatewayResult> forward(String serviceId, GatewayRequest request);
}
