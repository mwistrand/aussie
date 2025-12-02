package aussie.proxy;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import aussie.forwarding.ForwardedHeaderBuilderFactory;
import aussie.routing.model.RouteMatch;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

@ApplicationScoped
public class ProxyHttpClient {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailer", "transfer-encoding", "upgrade"
    );

    private final Vertx vertx;
    private final ForwardedHeaderBuilderFactory headerBuilderFactory;
    private WebClient webClient;

    @Inject
    public ProxyHttpClient(Vertx vertx, ForwardedHeaderBuilderFactory headerBuilderFactory) {
        this.vertx = vertx;
        this.headerBuilderFactory = headerBuilderFactory;
    }

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    public Uni<ProxyResponse> forward(ContainerRequestContext originalRequest, RouteMatch route, byte[] body) {
        var targetUri = route.targetUri();
        var method = HttpMethod.valueOf(originalRequest.getMethod());

        var request = createRequest(method, targetUri);
        copyHeaders(originalRequest, request, targetUri);
        addForwardingHeaders(originalRequest, request, targetUri);

        return executeRequest(request, body);
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

    private void copyHeaders(ContainerRequestContext originalRequest, HttpRequest<Buffer> proxyRequest, URI targetUri) {
        for (var entry : originalRequest.getHeaders().entrySet()) {
            var headerName = entry.getKey();
            var lowerName = headerName.toLowerCase();

            // Skip hop-by-hop headers
            if (HOP_BY_HOP_HEADERS.contains(lowerName)) {
                continue;
            }

            // Skip Host header (will be set for target)
            if ("host".equals(lowerName)) {
                continue;
            }

            // Skip Content-Length as it will be set by the client
            if ("content-length".equals(lowerName)) {
                continue;
            }

            for (var value : entry.getValue()) {
                proxyRequest.putHeader(headerName, value);
            }
        }

        // Set Host header for target
        var port = targetUri.getPort();
        var host = targetUri.getHost();
        if (port != -1 && port != 80 && port != 443) {
            host += ":" + port;
        }
        proxyRequest.putHeader("Host", host);
    }

    private void addForwardingHeaders(ContainerRequestContext originalRequest, HttpRequest<Buffer> proxyRequest, URI targetUri) {
        var headerBuilder = headerBuilderFactory.getBuilder();
        var forwardingHeaders = headerBuilder.buildHeaders(originalRequest, targetUri);

        for (var entry : forwardingHeaders.entrySet()) {
            proxyRequest.putHeader(entry.getKey(), entry.getValue());
        }
    }

    private Uni<ProxyResponse> executeRequest(HttpRequest<Buffer> request, byte[] body) {
        if (body != null && body.length > 0) {
            return request.sendBuffer(Buffer.buffer(body))
                .map(this::toProxyResponse);
        }

        return request.send()
            .map(this::toProxyResponse);
    }

    private ProxyResponse toProxyResponse(HttpResponse<Buffer> response) {
        Map<String, List<String>> headers = new HashMap<>();

        for (var name : response.headers().names()) {
            var lowerName = name.toLowerCase();
            // Skip hop-by-hop headers in response
            if (HOP_BY_HOP_HEADERS.contains(lowerName)) {
                continue;
            }

            headers.computeIfAbsent(name, k -> new ArrayList<>())
                .addAll(response.headers().getAll(name));
        }

        var responseBody = response.body() != null ? response.body().getBytes() : new byte[0];

        return new ProxyResponse(response.statusCode(), headers, responseBody);
    }
}
