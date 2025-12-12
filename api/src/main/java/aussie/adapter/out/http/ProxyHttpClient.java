package aussie.adapter.out.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;

import aussie.adapter.out.telemetry.SpanAttributes;
import aussie.core.model.PreparedProxyRequest;
import aussie.core.model.ProxyResponse;
import aussie.core.port.out.ProxyClient;
import aussie.core.service.ProxyRequestPreparer;

/**
 * HTTP adapter for forwarding prepared proxy requests using Vert.x WebClient.
 * All header preparation logic is handled by {@link ProxyRequestPreparer} in core.
 *
 * <p>This adapter propagates W3C Trace Context headers (traceparent, tracestate)
 * to downstream services for distributed tracing.
 */
@ApplicationScoped
public class ProxyHttpClient implements ProxyClient {

    private static final TextMapSetter<HttpRequest<Buffer>> HEADER_SETTER =
            (carrier, key, value) -> carrier.putHeader(key, value);

    private final Vertx vertx;
    private final ProxyRequestPreparer requestPreparer;
    private final Tracer tracer;
    private final TextMapPropagator propagator;
    private WebClient webClient;

    @Inject
    public ProxyHttpClient(
            Vertx vertx, ProxyRequestPreparer requestPreparer, Tracer tracer, TextMapPropagator propagator) {
        this.vertx = vertx;
        this.requestPreparer = requestPreparer;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    @Override
    public Uni<ProxyResponse> forward(PreparedProxyRequest preparedRequest) {
        var targetUri = preparedRequest.targetUri();
        var method = HttpMethod.valueOf(preparedRequest.method());

        // Create a client span for the outgoing request
        var span = tracer.spanBuilder("HTTP " + preparedRequest.method())
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SpanAttributes.HTTP_METHOD, preparedRequest.method())
                .setAttribute(SpanAttributes.HTTP_URL, targetUri.toString())
                .setAttribute(SpanAttributes.NET_PEER_NAME, targetUri.getHost())
                .setAttribute(SpanAttributes.NET_PEER_PORT, (long) getPort(targetUri))
                .startSpan();

        var request = createRequest(method, targetUri);
        applyHeaders(preparedRequest, request);

        // Propagate trace context (W3C Trace Context headers)
        propagator.inject(Context.current().with(span), request, HEADER_SETTER);

        return executeRequest(request, preparedRequest.body())
                .invoke(response -> {
                    span.setAttribute(SpanAttributes.HTTP_STATUS_CODE, (long) response.statusCode());
                    if (response.statusCode() >= 400) {
                        span.setStatus(StatusCode.ERROR, "HTTP " + response.statusCode());
                    }
                    span.end();
                })
                .onFailure()
                .invoke(error -> {
                    span.setStatus(StatusCode.ERROR, error.getMessage());
                    span.recordException(error);
                    span.end();
                });
    }

    private int getPort(URI uri) {
        var port = uri.getPort();
        if (port == -1) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return port;
    }

    private HttpRequest<Buffer> createRequest(HttpMethod method, URI targetUri) {
        var path = targetUri.getRawPath();
        if (targetUri.getRawQuery() != null) {
            path += "?" + targetUri.getRawQuery();
        }

        return webClient.request(method, getPort(targetUri), targetUri.getHost(), path);
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
