package aussie.core.port.out;

import io.smallrye.mutiny.Uni;

import aussie.core.model.gateway.PreparedProxyRequest;
import aussie.core.model.gateway.ProxyResponse;

public interface ProxyClient {

    Uni<ProxyResponse> forward(PreparedProxyRequest request);
}
