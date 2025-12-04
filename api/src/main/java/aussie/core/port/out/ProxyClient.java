package aussie.core.port.out;

import aussie.core.model.ProxyResponse;
import aussie.core.model.RouteMatch;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.container.ContainerRequestContext;

public interface ProxyClient {

    Uni<ProxyResponse> forward(ContainerRequestContext request, RouteMatch route, byte[] body);
}
