package aussie.adapter.out.http;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

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
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.jboss.logging.Logger;

import aussie.adapter.out.telemetry.SpanAttributes;
import aussie.adapter.out.telemetry.TelemetryHelper;
import aussie.core.config.ResiliencyConfig;
import aussie.core.model.gateway.PreparedProxyRequest;
import aussie.core.model.gateway.ProxyResponse;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.OutboundHttpClients;
import aussie.core.port.out.ProxyClient;
import aussie.core.service.gateway.ProxyRequestPreparer;
import aussie.core.service.routing.UpstreamAddressResolver;

/**
 * HTTP adapter for forwarding prepared proxy requests using Vert.x WebClient.
 * All header preparation logic is handled by {@link ProxyRequestPreparer} in core.
 *
 * <p>This adapter propagates W3C Trace Context headers (traceparent, tracestate)
 * to downstream services for distributed tracing.
 */
@ApplicationScoped
public class ProxyHttpClient implements ProxyClient {

    private static final Logger LOG = Logger.getLogger(ProxyHttpClient.class);
    private static final TextMapSetter<HttpRequest<Buffer>> HEADER_SETTER =
            (carrier, key, value) -> carrier.putHeader(key, value);

    private final ProxyRequestPreparer requestPreparer;
    private final Tracer tracer;
    private final TextMapPropagator propagator;
    private final TelemetryHelper telemetryHelper;
    private final ResiliencyConfig.HttpConfig httpConfig;
    private final Metrics metrics;
    private final UpstreamAddressResolver addressResolver;
    private final WebClient webClient;

    @Inject
    public ProxyHttpClient(
            OutboundHttpClients outboundClient,
            ProxyRequestPreparer requestPreparer,
            Tracer tracer,
            TextMapPropagator propagator,
            TelemetryHelper telemetryHelper,
            ResiliencyConfig resiliencyConfig,
            Metrics metrics,
            UpstreamAddressResolver addressResolver) {
        this.webClient = outboundClient.webClient();
        this.requestPreparer = requestPreparer;
        this.tracer = tracer;
        this.propagator = propagator;
        this.telemetryHelper = telemetryHelper;
        this.httpConfig = resiliencyConfig.http();
        this.metrics = metrics;
        this.addressResolver = addressResolver;
    }

    /**
     * Forward a prepared proxy request to the upstream service.
     *
     * <p>Applies configured timeouts:
     * <ul>
     *   <li>Connect timeout: maximum time to establish TCP connection</li>
     *   <li>Request timeout: maximum time to receive response from upstream</li>
     * </ul>
     *
     * <p>On timeout, returns 504 Gateway Timeout and records metrics. On connection
     * failure (refused, reset, etc.), propagates the error to the caller after
     * recording metrics and closing the trace span.
     *
     * @param preparedRequest the prepared request with target URI, headers, and body
     * @return the proxy response, or 504 Gateway Timeout on request timeout
     */
    @Override
    public Uni<ProxyResponse> forward(PreparedProxyRequest preparedRequest) {
        var targetUri = preparedRequest.targetUri();
        var method = HttpMethod.valueOf(preparedRequest.method());
        var effectiveTimeout = preparedRequest.requestTimeout().orElse(httpConfig.requestTimeout());
        final var startTime = System.currentTimeMillis();

        // Create a client span for the outgoing request
        var span = tracer.spanBuilder("HTTP " + preparedRequest.method())
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SpanAttributes.HTTP_METHOD, preparedRequest.method())
                .setAttribute(SpanAttributes.HTTP_URL, safeTelemetryUri(targetUri))
                .setAttribute(SpanAttributes.NET_PEER_NAME, targetUri.getHost())
                .setAttribute(SpanAttributes.NET_PEER_PORT, (long) getPort(targetUri))
                .startSpan();

        // Add configurable upstream attributes
        telemetryHelper.setUpstreamHost(span, targetUri.getHost());
        telemetryHelper.setUpstreamPort(span, getPort(targetUri));
        telemetryHelper.setUpstreamUri(span, safeTelemetryUri(targetUri));
        if (preparedRequest.body() != null) {
            telemetryHelper.setRequestSize(span, preparedRequest.body().length);
        }

        // Use target host as service identifier for metrics
        final var serviceIdentifier = targetUri.getHost();

        return addressResolver
                .resolve(targetUri)
                .flatMap(serverAddress -> {
                    final var request = createRequest(method, targetUri, effectiveTimeout, serverAddress);
                    applyHeaders(preparedRequest, request);
                    propagator.inject(Context.current().with(span), request, HEADER_SETTER);
                    return executeRequest(request, preparedRequest.body());
                })
                .ifNoItem()
                .after(effectiveTimeout)
                .failWith(() -> new TimeoutException("Request timed out"))
                .invoke(response -> {
                    span.setAttribute(SpanAttributes.HTTP_STATUS_CODE, (long) response.statusCode());
                    telemetryHelper.setUpstreamLatency(span, System.currentTimeMillis() - startTime);
                    telemetryHelper.setResponseSize(span, response.body().length);
                    if (response.statusCode() >= 400) {
                        span.setStatus(StatusCode.ERROR, "HTTP " + response.statusCode());
                    }
                    span.end();
                })
                .onFailure(this::isTimeoutException)
                .recoverWithItem(error -> {
                    LOG.warnv("Request timeout for upstream {0}: {1}", serviceIdentifier, error.getMessage());
                    metrics.recordProxyTimeout(serviceIdentifier, "request");
                    telemetryHelper.setUpstreamLatency(span, System.currentTimeMillis() - startTime);
                    span.setStatus(StatusCode.ERROR, "Gateway Timeout");
                    span.recordException(error);
                    span.end();
                    return new ProxyResponse(
                            504, Map.of("Content-Type", List.of("text/plain")), "Gateway Timeout".getBytes());
                })
                .onFailure()
                .invoke(error -> {
                    LOG.warnv("Connection failure for upstream {0}: {1}", serviceIdentifier, error.getMessage());
                    metrics.recordProxyConnectionFailure(serviceIdentifier, classifyConnectionError(error));
                    telemetryHelper.setUpstreamLatency(span, System.currentTimeMillis() - startTime);
                    span.setStatus(StatusCode.ERROR, "Bad Gateway");
                    span.recordException(error);
                    span.end();
                });
    }

    private boolean isTimeoutException(Throwable error) {
        var current = error;
        while (current != null) {
            if (current instanceof TimeoutException || current instanceof io.netty.channel.ConnectTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String classifyConnectionError(Throwable error) {
        for (var current = error; current != null; current = current.getCause()) {
            final var message =
                    current.getMessage() != null ? current.getMessage().toLowerCase(Locale.ROOT) : "";
            final var className = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);

            if (message.contains("ssl")
                    || message.contains("tls")
                    || message.contains("certificate")
                    || className.contains("ssl")) {
                return "tls_handshake_failed";
            }
            if (message.contains("refused") || className.contains("refused")) {
                return "connection_refused";
            }
            if (message.contains("reset") || className.contains("reset")) {
                return "connection_reset";
            }
            if (message.contains("unreachable") || className.contains("unreachable")) {
                return "host_unreachable";
            }
            if (message.contains("resolve") || message.contains("unknown host") || className.contains("unknownhost")) {
                return "dns_resolution_failed";
            }
        }
        return "connection_error";
    }

    private int getPort(URI uri) {
        var port = uri.getPort();
        if (port == -1) {
            port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        }
        return port;
    }

    private String safeTelemetryUri(URI uri) {
        final var path = uri.getRawPath();
        final var port = uri.getPort() == -1 ? "" : ":" + uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + port + (path == null ? "" : path);
    }

    private HttpRequest<Buffer> createRequest(
            HttpMethod method,
            URI targetUri,
            java.time.Duration timeout,
            io.vertx.core.net.SocketAddress serverAddress) {
        var path = targetUri.getRawPath();
        if (targetUri.getRawQuery() != null) {
            path += "?" + targetUri.getRawQuery();
        }

        return webClient
                .request(
                        method,
                        io.vertx.mutiny.core.net.SocketAddress.newInstance(serverAddress),
                        getPort(targetUri),
                        targetUri.getHost(),
                        path)
                .ssl("https".equalsIgnoreCase(targetUri.getScheme()))
                .followRedirects(false)
                .timeout(timeout.toMillis());
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
