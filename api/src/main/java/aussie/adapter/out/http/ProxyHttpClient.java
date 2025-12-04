package aussie.adapter.out.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;

import aussie.core.model.PreparedProxyRequest;
import aussie.core.model.ProxyResponse;
import aussie.core.port.out.ProxyClient;
import aussie.core.service.ProxyRequestPreparer;

/**
 * HTTP adapter for forwarding prepared proxy requests using Vert.x WebClient.
 * All header preparation logic is handled by {@link ProxyRequestPreparer} in core.
 */
@ApplicationScoped
public class ProxyHttpClient implements ProxyClient {

    private final Vertx vertx;
    private final ProxyRequestPreparer requestPreparer;
    private WebClient webClient;

    @Inject
    public ProxyHttpClient(Vertx vertx, ProxyRequestPreparer requestPreparer) {
        this.vertx = vertx;
        this.requestPreparer = requestPreparer;
    }

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    @Override
    public Uni<ProxyResponse> forward(PreparedProxyRequest preparedRequest) {
        var targetUri = preparedRequest.targetUri();
        var method = HttpMethod.valueOf(preparedRequest.method());

        var request = createRequest(method, targetUri);
        applyHeaders(preparedRequest, request);

        return executeRequest(request, preparedRequest.body());
    }

    private HttpRequest<Buffer> createRequest(HttpMethod method, URI targetUri) {
        var port = targetUri.getPort();
        if (port == -1) {
            port = "https".equalsIgnoreCase(targetUri.getScheme()) ? 443 : 80;
        }

        var path = targetUri.getRawPath();
        if (targetUri.getRawQuery() != null) {
            path += "?" + targetUri.getRawQuery();
        }

        return webClient.request(method, port, targetUri.getHost(), path);
    }

    private void applyHeaders(PreparedProxyRequest preparedRequest, HttpRequest<Buffer> httpRequest) {
        for (var entry : preparedRequest.headers().entrySet()) {
            for (var value : entry.getValue()) {
                httpRequest.putHeader(entry.getKey(), value);
            }
        }
    }

    private Uni<ProxyResponse> executeRequest(HttpRequest<Buffer> request, byte[] body) {
        if (body != null && body.length > 0) {
            return request.sendBuffer(Buffer.buffer(body)).map(this::toProxyResponse);
        }

        return request.send().map(this::toProxyResponse);
    }

    private ProxyResponse toProxyResponse(HttpResponse<Buffer> response) {
        Map<String, List<String>> headers = new HashMap<>();

        for (var name : response.headers().names()) {
            headers.computeIfAbsent(name, k -> new ArrayList<>())
                    .addAll(response.headers().getAll(name));
        }

        var filteredHeaders = requestPreparer.filterResponseHeaders(headers);
        var responseBody = response.body() != null ? response.body().getBytes() : new byte[0];

        return new ProxyResponse(response.statusCode(), filteredHeaders, responseBody);
    }
}
