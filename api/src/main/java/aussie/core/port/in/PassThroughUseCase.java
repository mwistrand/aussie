package aussie.core.port.in;

import io.smallrye.mutiny.Uni;

import aussie.core.model.GatewayRequest;
import aussie.core.model.GatewayResult;

public interface PassThroughUseCase {

    Uni<GatewayResult> forward(String serviceId, GatewayRequest request);
}
