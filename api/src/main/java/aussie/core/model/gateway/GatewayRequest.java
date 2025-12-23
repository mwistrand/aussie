package aussie.core.model.gateway;

import java.net.URI;
import java.util.List;
import java.util.Map;

public record GatewayRequest(
        String method, String path, Map<String, List<String>> headers, URI requestUri, byte[] body, String clientIp) {

    public GatewayRequest {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("method is required");
        }
        if (path == null) {
            path = "/";
        }
        if (headers == null) {
            headers = Map.of();
        }
        if (body == null) {
            body = new byte[0];
        }
    }

    public String getHeaderString(String name) {
        var values = headers.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }
}
