package aussie.core.model.gateway;

import java.util.List;
import java.util.Map;

public record ProxyResponse(int statusCode, Map<String, List<String>> headers, byte[] body) {
    public ProxyResponse {
        if (headers == null) {
            headers = Map.of();
        }
        if (body == null) {
            body = new byte[0];
        }
    }
}
