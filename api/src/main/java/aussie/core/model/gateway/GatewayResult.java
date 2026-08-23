package aussie.core.model.gateway;

import java.util.List;
import java.util.Map;

public sealed interface GatewayResult {

    record Success(int statusCode, Map<String, List<String>> headers, byte[] body) implements GatewayResult {
        public Success {
            if (headers == null) {
                headers = Map.of();
            }
            if (body == null) {
                body = new byte[0];
            }
        }
    }

    record RouteNotFound(String path) implements GatewayResult {}

    record ServiceNotFound(String serviceId) implements GatewayResult {}

    record ReservedPath(String path) implements GatewayResult {}

    record Error(String message) implements GatewayResult {}

    record Unauthorized(String reason) implements GatewayResult {}

    record Forbidden(String reason) implements GatewayResult {}

    record BadRequest(String reason) implements GatewayResult {}
}
