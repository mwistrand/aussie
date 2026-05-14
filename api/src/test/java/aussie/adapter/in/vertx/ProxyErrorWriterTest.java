package aussie.adapter.in.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;

import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.problem.ProblemDetail;
import aussie.adapter.in.problem.ProblemJson;
import aussie.adapter.out.telemetry.GatewayMetrics;

@DisplayName("ProxyErrorWriter")
@ExtendWith(MockitoExtension.class)
class ProxyErrorWriterTest {

    @Mock
    private GatewayMetrics metrics;

    @Mock
    private RoutingContext ctx;

    @Mock
    private HttpServerResponse response;

    @Mock
    private HttpServerRequest request;

    private ProxyErrorWriter writer;

    @BeforeEach
    void setUp() {
        writer = new ProxyErrorWriter(metrics);
    }

    private void stubChain() {
        when(ctx.response()).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
        when(response.putHeader(any(CharSequence.class), any(CharSequence.class)))
                .thenReturn(response);
    }

    @Nested
    @DisplayName("write")
    class WriteTest {

        @Test
        @DisplayName("sets status, content-type, and ends with serialized JSON body")
        void writesStatusAndBody() {
            stubChain();
            when(metrics.isEnabled()).thenReturn(true);
            var problem = ProblemDetail.serviceNotFound("missing-service");

            writer.write(ctx, problem, "my-service");

            verify(response).setStatusCode(404);
            verify(response).putHeader(HttpHeaders.CONTENT_TYPE, (CharSequence) ProblemJson.CONTENT_TYPE);
            verify(metrics).recordError("my-service", "Service Not Found");
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(response).end(bodyCaptor.capture());
            assertEquals(ProblemJson.serialize(problem), bodyCaptor.getValue());
        }

        @Test
        @DisplayName("falls back to 'unknown' service id when none is supplied")
        void unknownServiceWhenNullId() {
            stubChain();
            when(metrics.isEnabled()).thenReturn(true);

            writer.write(ctx, ProblemDetail.routeNotFound("/missing"));

            verify(metrics).recordError(eq("unknown"), anyString());
        }

        @Test
        @DisplayName("is a no-op when the response is already committed")
        void noOpWhenAlreadyEnded() {
            when(ctx.response()).thenReturn(response);
            when(response.ended()).thenReturn(true);
            when(ctx.request()).thenReturn(request);
            when(request.uri()).thenReturn("/foo");

            writer.write(ctx, ProblemDetail.internalError("boom"));

            verify(response, never()).setStatusCode(anyInt());
            verify(response, never()).end(any(String.class));
            verify(metrics, never()).recordError(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("writeRateLimit")
    class WriteRateLimitTest {

        @Test
        @DisplayName("emits 429 with Retry-After + full X-RateLimit-* header set")
        void emitsRateLimitHeaders() {
            stubChain();
            when(metrics.isEnabled()).thenReturn(true);
            var extras = new LinkedHashMap<String, Object>();
            extras.put("retryAfter", 30L);
            extras.put("limit", 100L);
            extras.put("resetAt", 1700000000L);
            var problem = new ProblemDetail("Too Many Requests", 429, "throttled", extras);

            writer.writeRateLimit(ctx, problem, "my-service", 30L, 100L, 1700000000L);

            verify(response).setStatusCode(429);
            verify(response).putHeader(eq(HttpHeaders.CONTENT_TYPE), any(CharSequence.class));
            verify(response).putHeader(eq(HttpHeaders.RETRY_AFTER), eq((CharSequence) "30"));
            verify(response)
                    .putHeader(
                            argThat((CharSequence h) -> "X-RateLimit-Limit".contentEquals(h)),
                            argThat((CharSequence v) -> "100".contentEquals(v)));
            verify(response)
                    .putHeader(
                            argThat((CharSequence h) -> "X-RateLimit-Remaining".contentEquals(h)),
                            argThat((CharSequence v) -> "0".contentEquals(v)));
            verify(response)
                    .putHeader(
                            argThat((CharSequence h) -> "X-RateLimit-Reset".contentEquals(h)),
                            argThat((CharSequence v) -> "1700000000".contentEquals(v)));
            verify(metrics).recordError("my-service", "Too Many Requests");
            var bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(response).end(bodyCaptor.capture());
            assertNotNull(bodyCaptor.getValue());
            assertEquals(ProblemJson.serialize(problem), bodyCaptor.getValue());
        }
    }
}
