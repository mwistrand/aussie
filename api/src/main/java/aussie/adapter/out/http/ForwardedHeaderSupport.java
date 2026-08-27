package aussie.adapter.out.http;

import aussie.core.model.gateway.GatewayRequest;

final class ForwardedHeaderSupport {

    private ForwardedHeaderSupport() {}

    static String protocol(GatewayRequest request) {
        if (request.externalScheme() != null) {
            return request.externalScheme();
        }

        final var requestUri = request.requestUri();
        if (requestUri != null) {
            return requestUri.getScheme();
        }

        return "http";
    }
}
