package aussie.core.port.in;

import io.smallrye.mutiny.Uni;

import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;

public interface GatewayUseCase {

    Uni<GatewayResult> forward(GatewayRequest request);
}
