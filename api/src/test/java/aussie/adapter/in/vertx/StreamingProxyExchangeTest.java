package aussie.adapter.in.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.telemetry.TelemetryHelper;
import aussie.core.config.ResiliencyConfig;
import aussie.core.model.common.LimitsConfig;
import aussie.core.model.gateway.GatewayResult;
import aussie.core.model.gateway.ProxyPlan;
import aussie.core.port.out.AuthenticatedContext;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.OutboundHttpClients;
import aussie.core.port.out.TrafficAttributing;
import aussie.core.service.gateway.ProxyRequestPreparer;
import aussie.core.service.routing.UpstreamAddressResolver;

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
        final var exchange = new StreamingProxyExchange(
                clients,
                mock(ProxyRequestPreparer.class),
                mock(UpstreamAddressResolver.class),
                mock(ResiliencyConfig.class),
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
}
