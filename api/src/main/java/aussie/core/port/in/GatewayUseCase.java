package aussie.core.port.in;

import io.smallrye.mutiny.Uni;

import aussie.core.model.GatewayRequest;
import aussie.core.model.GatewayResult;

public interface GatewayUseCase {

    Uni<GatewayResult> forward(GatewayRequest request);
}
