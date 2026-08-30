package aussie.adapter.in.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.Tracer;
import io.quarkiverse.httpproblem.HttpProblem;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.core.http.HttpClientResponse;
import io.vertx.mutiny.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.http.UpstreamAddressResolver;
import aussie.adapter.out.telemetry.TelemetryHelper;
import aussie.core.config.LimitsConfig;
import aussie.core.config.ResiliencyConfig;
import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.PreparedProxyRequest;
import aussie.core.model.gateway.ProxyPlan;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.AuthenticatedContext;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.OutboundHttpClients;
import aussie.core.port.out.TrafficAttributing;
import aussie.core.service.gateway.ProxyRequestPreparer;

class StreamingProxyExchangeTest {

    @Test
    void boundsUnknownLengthBodiesWhileChunksArrive() {
        final var body = Multi.createFrom().items(Buffer.buffer("1234"), Buffer.buffer("5678"));

        final var failure = assertThrows(HttpProblem.class, () -> StreamingProxyExchange.bounded(body, 7, "Request")
                .collect()
                .asList()
                .await()
                .atMost(Duration.ofSeconds(1)));

        assertTrue(failure.getMessage().contains("maximum allowed size 7"));
    }

    @Test
    void passesChunksWithoutJoiningThem() {
        final var body = Multi.createFrom().items(Buffer.buffer("1234"), Buffer.buffer("5678"));

        final var chunks = StreamingProxyExchange.bounded(body, 8, "Request")
                .collect()
                .asList()
                .await()
                .atMost(Duration.ofSeconds(1));

        assertEquals(2, chunks.size());
    }

    @Test
    void keepsTheByteCountLocalToEachSubscription() {
        final var bounded =
                StreamingProxyExchange.bounded(Multi.createFrom().item(Buffer.buffer("1234")), 4, "Request");

        assertEquals(
                1,
                bounded.collect().asList().await().atMost(Duration.ofSeconds(1)).size());
        assertEquals(
                1,
                bounded.collect().asList().await().atMost(Duration.ofSeconds(1)).size());
    }

    @Test
    void resumesTheInboundRequestWhenPreparationRejectsIt() {
        final var clients = mock(OutboundHttpClients.class);
        when(clients.httpClient()).thenReturn(mock(io.vertx.core.http.HttpClient.class));
        final var metrics = mock(Metrics.class);
        final var request = mock(HttpServerRequest.class);
        when(request.pause()).thenReturn(request);
        when(request.resume()).thenReturn(request);
        final var resiliency = resiliency();
        final var exchange = new StreamingProxyExchange(
                clients,
                mock(ProxyRequestPreparer.class),
                mock(UpstreamAddressResolver.class),
                resiliency,
                mock(LimitsConfig.class),
                metrics,
                mock(TrafficAttributing.class),
                mock(AuthenticatedContext.class),
                mock(io.opentelemetry.api.trace.Tracer.class),
                mock(io.opentelemetry.context.propagation.TextMapPropagator.class),
                mock(TelemetryHelper.class));
        final var result = new GatewayResult.RouteNotFound("/missing");

        final var failure = assertThrows(HttpProblem.class, () -> exchange.forward(
                        Uni.createFrom().item(new ProxyPlan.Rejected(result, null)), request, false)
                .collect()
                .asList()
                .await()
                .atMost(Duration.ofSeconds(1)));

        assertEquals(404, failure.getStatusCode());
        verify(request).pause();
        verify(request).resume();
        verify(metrics).recordGatewayResult(null, result);
    }

    @Test
    void rejectsKnownOversizeRequestsBeforeOpeningTheUpstream() {
        final var clients = mock(OutboundHttpClients.class);
        when(clients.httpClient()).thenReturn(mock(io.vertx.core.http.HttpClient.class));
        final var limits = mock(LimitsConfig.class);
        when(limits.maxBodySize()).thenReturn(7L);
        final var request = mock(HttpServerRequest.class);
        when(request.pause()).thenReturn(request);
        when(request.resume()).thenReturn(request);
        when(request.getHeader("Content-Length")).thenReturn("8");
        final var resiliency = resiliency();
        final var exchange = new StreamingProxyExchange(
                clients,
                mock(ProxyRequestPreparer.class),
                mock(UpstreamAddressResolver.class),
                resiliency,
                limits,
                mock(Metrics.class),
                mock(TrafficAttributing.class),
                mock(AuthenticatedContext.class),
                mock(io.opentelemetry.api.trace.Tracer.class),
                mock(io.opentelemetry.context.propagation.TextMapPropagator.class),
                mock(TelemetryHelper.class));

        final var failure = assertThrows(HttpProblem.class, () -> exchange.forward(
                        Uni.createFrom().failure(new AssertionError("preparation must not run")), request, false)
                .collect()
                .asList()
                .await()
                .atMost(Duration.ofSeconds(1)));

        assertEquals(413, failure.getStatusCode());
        verify(request).resume();
    }

    @Test
    void suppressesBodiesForbiddenByHttpSemantics() {
        final var response = mock(HttpClientResponse.class);

        when(response.statusCode()).thenReturn(199, 204, 205, 304, 200);

        assertTrue(StreamingProxyExchange.hasNoResponseBody(response, false));
        assertTrue(StreamingProxyExchange.hasNoResponseBody(response, false));
        assertTrue(StreamingProxyExchange.hasNoResponseBody(response, false));
        assertTrue(StreamingProxyExchange.hasNoResponseBody(response, false));
        assertTrue(StreamingProxyExchange.hasNoResponseBody(response, true));
    }

    @Test
    void endsTheUpstreamSpanWhenOpeningIsCancelled() {
        final var clients = mock(OutboundHttpClients.class);
        when(clients.httpClient()).thenReturn(mock(io.vertx.core.http.HttpClient.class));
        final var resolver = mock(UpstreamAddressResolver.class);
        when(resolver.resolve(any())).thenReturn(Uni.createFrom().nothing());
        final var request = mock(HttpServerRequest.class);
        when(request.pause()).thenReturn(request);
        when(request.resume()).thenReturn(request);
        when(request.toMulti()).thenReturn(Multi.createFrom().nothing());
        final var tracer = mock(Tracer.class);
        final var spanBuilder = mock(SpanBuilder.class);
        final var span = mock(Span.class);
        when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setSpanKind(any())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setAttribute(anyString(), anyLong())).thenReturn(spanBuilder);
        when(spanBuilder.startSpan()).thenReturn(span);
        final var resiliency = resiliency();
        when(resiliency.http().requestTimeout()).thenReturn(Duration.ofSeconds(30));
        final var exchange = new StreamingProxyExchange(
                clients,
                mock(ProxyRequestPreparer.class),
                resolver,
                resiliency,
                mock(LimitsConfig.class),
                mock(Metrics.class),
                mock(TrafficAttributing.class),
                mock(AuthenticatedContext.class),
                tracer,
                mock(io.opentelemetry.context.propagation.TextMapPropagator.class),
                mock(TelemetryHelper.class));
        final var original = new GatewayRequest("GET", "/", Map.of(), URI.create("http://gateway/"), null, null);
        final var prepared = new PreparedProxyRequest("GET", URI.create("http://backend/"), Map.of(), null);
        final var plan = new ProxyPlan.Ready(
                original,
                prepared,
                ServiceRegistration.builder("backend").baseUrl("http://backend").build());

        final var failure = new AtomicReference<Throwable>();
        final var subscription = exchange.forward(Uni.createFrom().item(plan), request, false)
                .subscribe()
                .with(ignored -> {}, failure::set);
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        verify(resolver, timeout(1_000)).resolve(any());
        subscription.cancel();

        verify(span).end();
        verify(request).resume();
    }

    private ResiliencyConfig resiliency() {
        final var resiliency = mock(ResiliencyConfig.class);
        final var http = mock(ResiliencyConfig.HttpConfig.class);
        when(http.maxConnections()).thenReturn(1);
        when(resiliency.http()).thenReturn(http);
        return resiliency;
    }
}
