package aussie.core.port.out;

import java.net.URI;
import java.util.Map;

import jakarta.ws.rs.container.ContainerRequestContext;

public interface ForwardedHeaderBuilder {

    /**
     * Builds forwarding headers to include in the proxied request.
     *
     * @param originalRequest the original incoming request
     * @param targetUri the target URI being forwarded to
     * @return map of header names to header values
     */
    Map<String, String> buildHeaders(ContainerRequestContext originalRequest, URI targetUri);
}
