package aussie.core.port.out;

import jakarta.ws.rs.container.ContainerRequestContext;

import io.smallrye.mutiny.Uni;

import aussie.core.model.ProxyResponse;
import aussie.core.model.RouteMatch;

public interface ProxyClient {

    Uni<ProxyResponse> forward(ContainerRequestContext request, RouteMatch route, byte[] body);
}
