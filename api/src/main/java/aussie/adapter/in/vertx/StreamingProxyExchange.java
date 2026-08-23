package aussie.adapter.in.vertx;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.quarkiverse.resteasy.problem.HttpProblem;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.core.http.HttpClient;
import io.vertx.mutiny.core.http.HttpClientResponse;
import io.vertx.mutiny.core.http.HttpServerRequest;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestMulti;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.adapter.out.telemetry.SpanAttributes;
import aussie.adapter.out.telemetry.TelemetryHelper;
import aussie.core.config.ResiliencyConfig;
import aussie.core.model.common.LimitsConfig;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.PreparedProxyRequest;
import aussie.core.model.gateway.ProxyPlan;
import aussie.core.port.out.AuthenticatedContext;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.OutboundHttpClients;
import aussie.core.port.out.TrafficAttributing;
import aussie.core.service.gateway.ProxyRequestPreparer;
import aussie.core.service.routing.UpstreamAddressResolver;

/** Streams one inbound HTTP exchange to its resolved upstream with demand-based flow control. */
@ApplicationScoped
public class StreamingProxyExchange {

    private static final Logger LOG = Logger.getLogger(StreamingProxyExchange.class);
    private static final int MAX_QUEUED_BYTES = 64 * 1024;
    private static final TextMapSetter<RequestOptions> HEADER_SETTER = RequestOptions::putHeader;

    private final HttpClient httpClient;
    private final ProxyRequestPreparer requestPreparer;
    private final UpstreamAddressResolver addressResolver;
    private final ResiliencyConfig.HttpConfig httpConfig;
    private final LimitsConfig limits;
    private final Metrics metrics;
    private final TrafficAttributing attributionService;
    private final AuthenticatedContext authenticatedContext;
    private final Tracer tracer;
    private final TextMapPropagator propagator;
    private final TelemetryHelper telemetryHelper;

    @Inject
    public StreamingProxyExchange(
            OutboundHttpClients clients,
            ProxyRequestPreparer requestPreparer,
            UpstreamAddressResolver addressResolver,
            ResiliencyConfig resiliencyConfig,
            LimitsConfig limits,
            Metrics metrics,
            TrafficAttributing attributionService,
            AuthenticatedContext authenticatedContext,
            Tracer tracer,
            TextMapPropagator propagator,
            TelemetryHelper telemetryHelper) {
        this.httpClient = HttpClient.newInstance(clients.httpClient());
        this.requestPreparer = requestPreparer;
        this.addressResolver = addressResolver;
        this.httpConfig = resiliencyConfig.http();
        this.limits = limits;
        this.metrics = metrics;
        this.attributionService = attributionService;
        this.authenticatedContext = authenticatedContext;
        this.tracer = tracer;
        this.propagator = propagator;
        this.telemetryHelper = telemetryHelper;
    }

    public Multi<io.vertx.core.buffer.Buffer> forward(
            Uni<ProxyPlan> prepared, HttpServerRequest request, boolean suppressResponseBody) {
        request.pause();
        final var response = Uni.createFrom()
                .deferred(() -> {
                    final long started = System.nanoTime();
                    final var requestBytes = new AtomicLong();
                    return prepared.flatMap(plan -> {
                        if (plan instanceof ProxyPlan.Rejected rejected) {
                            metrics.recordGatewayResult(rejected.serviceId(), rejected.result());
                            return Uni.createFrom().failure(GatewayProblem.from(rejected.result()));
                        }
                        final var ready = (ProxyPlan.Ready) plan;
                        return open(
                                ready,
                                bounded(request.toMulti(), limits.maxBodySize(), "Request", requestBytes),
                                suppressResponseBody,
                                requestBytes,
                                started);
                    });
                })
                .onFailure()
                .invoke(ignored -> request.resume())
                .onCancellation()
                .invoke(request::resume);
        return RestMulti.fromUniResponse(
                response,
                exchange -> responseBody(exchange, suppressResponseBody),
                exchange -> responseHeaders(exchange.response()),
                exchange -> exchange.response().statusCode());
    }

    private Uni<Exchange> open(
            ProxyPlan.Ready plan,
            Multi<Buffer> body,
            boolean suppressResponseBody,
            AtomicLong requestBytes,
            long started) {
        final var prepared = plan.request();
        final URI target = prepared.targetUri();
        final var timeout = prepared.requestTimeout().orElse(httpConfig.requestTimeout());
        final var span = startSpan(prepared);
        final var teamId = authenticatedContext.getTeamId();

        return addressResolver
                .resolve(target)
                .flatMap(server -> {
                    final var options = new RequestOptions()
                            .setMethod(HttpMethod.valueOf(prepared.method()))
                            .setServer(server)
                            .setHost(target.getHost())
                            .setPort(port(target))
                            .setSsl("https".equalsIgnoreCase(target.getScheme()))
                            .setURI(pathAndQuery(target))
                            .setFollowRedirects(false)
                            .setTimeout(timeout.toMillis());
                    prepared.headers()
                            .forEach((name, values) -> values.forEach(value -> options.addHeader(name, value)));
                    propagator.inject(Context.current().with(span), options, HEADER_SETTER);
                    return httpClient.request(options).flatMap(request -> {
                        request.setWriteQueueMaxSize(MAX_QUEUED_BYTES);
                        return request.send(body);
                    });
                })
                .ifNoItem()
                .after(timeout)
                .failWith(() -> new TimeoutException("Upstream request timed out"))
                .map(response -> {
                    rejectKnownOversizeResponse(response, suppressResponseBody);
                    return new Exchange(plan, response, span, teamId, requestBytes, started);
                })
                .onFailure()
                .invoke(error -> recordOpenFailure(plan, span, requestBytes.get(), started, error))
                .onFailure(this::isTimeout)
                .transform(error -> GatewayProblem.gatewayTimeout("Upstream request timed out"))
                .onFailure(error -> !(error instanceof HttpProblem))
                .transform(error -> GatewayProblem.badGateway("Upstream request failed"));
    }

    private Multi<io.vertx.core.buffer.Buffer> responseBody(Exchange exchange, boolean suppressResponseBody) {
        final var responseBytes = new AtomicLong();
        final var body =
                bounded(exchange.response().toMulti(), limits.maxResponseBodySize(), "Response", responseBytes);
        final Multi<io.vertx.core.buffer.Buffer> output = suppressResponseBody
                ? body.onItem().transformToMultiAndConcatenate(ignored -> Multi.createFrom()
                        .<io.vertx.core.buffer.Buffer>empty())
                : body.map(Buffer::getDelegate);
        return output.onTermination()
                .invoke((failure, cancelled) -> finish(exchange, responseBytes.get(), failure, cancelled));
    }

    private void finish(Exchange exchange, long responseBytes, Throwable failure, boolean cancelled) {
        final long durationMs = elapsedMillis(exchange.started());
        final var plan = exchange.plan();
        final var span = exchange.span();
        telemetryHelper.setRequestSize(span, exchange.requestBytes().get());
        telemetryHelper.setResponseSize(span, responseBytes);
        telemetryHelper.setUpstreamLatency(span, durationMs);

        if (failure == null && !cancelled) {
            final var statusCode = exchange.response().statusCode();
            metrics.recordGatewayResult(plan.serviceId(), new GatewayResult.Success(statusCode, Map.of(), null));
            metrics.recordRequest(plan.serviceId(), plan.request().method(), statusCode);
            metrics.recordProxyLatency(plan.serviceId(), plan.request().method(), statusCode, durationMs);
            if (attributionService.isEnabled()) {
                attributionService.record(
                        plan.originalRequest(),
                        plan.service(),
                        exchange.teamId(),
                        exchange.requestBytes().get(),
                        responseBytes,
                        durationMs);
            }
            span.setAttribute(SpanAttributes.HTTP_STATUS_CODE, (long) statusCode);
            if (statusCode >= 400) {
                span.setStatus(StatusCode.ERROR, "HTTP " + statusCode);
            }
        } else if (failure != null) {
            recordStreamFailure(plan, failure);
            span.setStatus(StatusCode.ERROR, "Upstream response failed");
            span.recordException(failure);
        }
        span.end();
    }

    private void recordOpenFailure(
            ProxyPlan.Ready plan, Span span, long requestBytes, long started, Throwable failure) {
        final long durationMs = elapsedMillis(started);
        telemetryHelper.setRequestSize(span, requestBytes);
        telemetryHelper.setResponseSize(span, 0);
        telemetryHelper.setUpstreamLatency(span, durationMs);
        span.setStatus(StatusCode.ERROR, "Upstream request failed");
        span.recordException(failure);
        span.end();

        if (isTimeout(failure)) {
            LOG.warnv("Request timeout for upstream service {0}", plan.serviceId());
            metrics.recordProxyTimeout(plan.serviceId(), "request");
            recordUpstreamError(plan.serviceId());
        } else if (!(failure instanceof HttpProblem)) {
            LOG.warnv("Connection failure for upstream service {0}: {1}", plan.serviceId(), failure.getMessage());
            metrics.recordProxyConnectionFailure(plan.serviceId(), classifyConnectionError(failure));
            recordUpstreamError(plan.serviceId());
        }
    }

    private void recordStreamFailure(ProxyPlan.Ready plan, Throwable failure) {
        if (isTimeout(failure)) {
            metrics.recordProxyTimeout(plan.serviceId(), "request");
        } else if (!(failure instanceof HttpProblem)) {
            metrics.recordProxyConnectionFailure(plan.serviceId(), classifyConnectionError(failure));
        }
        recordUpstreamError(plan.serviceId());
    }

    private void recordUpstreamError(String serviceId) {
        metrics.recordGatewayResult(serviceId, new GatewayResult.Error("Upstream request failed"));
        metrics.recordError(serviceId, "upstream_error");
    }

    private Span startSpan(PreparedProxyRequest prepared) {
        final var target = prepared.targetUri();
        final var span = tracer.spanBuilder("HTTP " + prepared.method())
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(SpanAttributes.HTTP_METHOD, prepared.method())
                .setAttribute(SpanAttributes.HTTP_URL, safeTelemetryUri(target))
                .setAttribute(SpanAttributes.NET_PEER_NAME, target.getHost())
                .setAttribute(SpanAttributes.NET_PEER_PORT, (long) port(target))
                .startSpan();
        telemetryHelper.setUpstreamHost(span, target.getHost());
        telemetryHelper.setUpstreamPort(span, port(target));
        telemetryHelper.setUpstreamUri(span, safeTelemetryUri(target));
        return span;
    }

    private void rejectKnownOversizeResponse(HttpClientResponse response, boolean suppressResponseBody) {
        if (suppressResponseBody) {
            return;
        }
        final var value = response.getHeader("Content-Length");
        if (value == null) {
            return;
        }
        try {
            if (Long.parseLong(value) > limits.maxResponseBodySize()) {
                response.request().reset();
                throw GatewayProblem.payloadTooLarge(
                        "Response body exceeds maximum allowed size " + limits.maxResponseBodySize());
            }
        } catch (NumberFormatException ignored) {
            // Vert.x validates malformed Content-Length headers before exposing a response.
        }
    }

    static Multi<Buffer> bounded(Multi<Buffer> body, long maximum, String direction) {
        return Multi.createFrom().deferred(() -> bounded(body, maximum, direction, new AtomicLong()));
    }

    private static Multi<Buffer> bounded(Multi<Buffer> body, long maximum, String direction, AtomicLong seen) {
        return body.onItem().transform(buffer -> {
            final long total;
            try {
                total = Math.addExact(seen.get(), buffer.length());
            } catch (ArithmeticException ignored) {
                throw GatewayProblem.payloadTooLarge(direction + " body exceeds maximum allowed size " + maximum);
            }
            if (total > maximum) {
                throw GatewayProblem.payloadTooLarge(direction + " body exceeds maximum allowed size " + maximum);
            }
            seen.set(total);
            return buffer;
        });
    }

    private Map<String, List<String>> responseHeaders(HttpClientResponse response) {
        final Map<String, List<String>> headers = new HashMap<>();
        for (final var name : response.headers().names()) {
            headers.computeIfAbsent(name, ignored -> new ArrayList<>())
                    .addAll(response.headers().getAll(name));
        }
        return requestPreparer.filterResponseHeaders(headers);
    }

    private boolean isTimeout(Throwable error) {
        for (var current = error; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException || current instanceof io.netty.channel.ConnectTimeoutException) {
                return true;
            }
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

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static int port(URI target) {
        return target.getPort() >= 0 ? target.getPort() : "https".equalsIgnoreCase(target.getScheme()) ? 443 : 80;
    }

    private static String pathAndQuery(URI target) {
        final var path = target.getRawPath().isEmpty() ? "/" : target.getRawPath();
        return target.getRawQuery() == null ? path : path + "?" + target.getRawQuery();
    }

    private static String safeTelemetryUri(URI target) {
        final var path = target.getRawPath();
        final var targetPort = target.getPort() == -1 ? "" : ":" + target.getPort();
        return target.getScheme() + "://" + target.getHost() + targetPort + (path == null ? "" : path);
    }

    private record Exchange(
            ProxyPlan.Ready plan,
            HttpClientResponse response,
            Span span,
            String teamId,
            AtomicLong requestBytes,
            long started) {}
}
