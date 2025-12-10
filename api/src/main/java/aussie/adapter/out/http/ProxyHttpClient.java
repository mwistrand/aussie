package aussie.adapter.out.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
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

import aussie.core.model.PreparedProxyRequest;
import aussie.core.model.ProxyResponse;
import aussie.core.port.out.ProxyClient;
import aussie.core.service.ProxyRequestPreparer;
import aussie.telemetry.SpanAttributes;

/**
 * HTTP adapter for forwarding prepared proxy requests using Vert.x WebClient.
 *
 * <p>This client handles:
 * <ul>
 *   <li>HTTP forwarding via Vert.x WebClient (non-blocking)</li>
 *   <li>OpenTelemetry trace context propagation</li>
 *   <li>Span creation for upstream requests</li>
 * </ul>
 *
 * <p>All header preparation logic is handled by {@link ProxyRequestPreparer} in core.
 */
@ApplicationScoped
public class ProxyHttpClient implements ProxyClient {

    /**
     * TextMapSetter for injecting trace context into outgoing HTTP requests.
     */
    private static final TextMapSetter<HttpRequest<Buffer>> CONTEXT_SETTER =
            (carrier, key, value) -> carrier.putHeader(key, value);

    private final Vertx vertx;
    private final ProxyRequestPreparer requestPreparer;
    private final Tracer tracer;
    private final TextMapPropagator propagator;
    private WebClient webClient;

    @Inject
    public ProxyHttpClient(Vertx vertx, ProxyRequestPreparer requestPreparer, OpenTelemetry openTelemetry) {
        this.vertx = vertx;
        this.requestPreparer = requestPreparer;
        this.tracer = openTelemetry.getTracer("aussie-gateway");
        this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
    }

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    @Override
    public Uni<ProxyResponse> forward(PreparedProxyRequest preparedRequest) {
        var targetUri = preparedRequest.targetUri();
        var method = HttpMethod.valueOf(preparedRequest.method());

        // Create span for the outbound HTTP request
        Span span = tracer.spanBuilder("HTTP " + preparedRequest.method() + " " + targetUri.getHost())
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SpanAttributes.HTTP_METHOD, preparedRequest.method())
                .setAttribute(SpanAttributes.HTTP_URL, targetUri.toString())
                .setAttribute(SpanAttributes.UPSTREAM_HOST, targetUri.getHost())
                .setAttribute(SpanAttributes.UPSTREAM_PORT, getPort(targetUri))
                .setAttribute(SpanAttributes.SERVICE_ID, preparedRequest.serviceId())
                .startSpan();

        long startTime = System.nanoTime();

        var request = createRequest(method, targetUri);

        // Inject trace context into outgoing headers
        propagator.inject(Context.current().with(span), request, CONTEXT_SETTER);

        // Apply prepared headers (after trace context to avoid overwriting)
        applyHeaders(preparedRequest, request);

        return executeRequest(request, preparedRequest.body())
                .invoke(response -> {
                    long latencyMs = (System.nanoTime() - startTime) / 1_000_000;
                    span.setAttribute(SpanAttributes.HTTP_STATUS_CODE, response.statusCode());
                    span.setAttribute(SpanAttributes.UPSTREAM_LATENCY_MS, latencyMs);
                    span.setAttribute(
                            SpanAttributes.RESPONSE_SIZE, response.body() != null ? response.body().length : 0);

                    if (response.statusCode() >= 400) {
                        span.setStatus(StatusCode.ERROR, "HTTP " + response.statusCode());
                    }
                    span.end();
                })
                .onFailure()
                .invoke(error -> {
                    span.recordException(error);
                    span.setStatus(StatusCode.ERROR, error.getMessage());
                    span.end();
                });
    }

    private int getPort(URI uri) {
        int port = uri.getPort();
        if (port == -1) {
            return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return port;
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
