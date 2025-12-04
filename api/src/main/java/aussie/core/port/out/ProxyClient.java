package aussie.core.port.out;

import io.smallrye.mutiny.Uni;

import aussie.core.model.PreparedProxyRequest;
import aussie.core.model.ProxyResponse;

public interface ProxyClient {

    Uni<ProxyResponse> forward(PreparedProxyRequest request);
}
