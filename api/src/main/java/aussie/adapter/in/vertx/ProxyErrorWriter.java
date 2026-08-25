package aussie.adapter.in.vertx;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.jboss.logging.Logger;

import aussie.adapter.in.problem.ProblemDetail;
import aussie.adapter.in.problem.ProblemJson;
import aussie.adapter.out.telemetry.GatewayMetrics;

/**
 * Write an RFC 9457 problem response to a Vert.x {@link RoutingContext}.
 *
 * <p>Used by the native Vert.x error paths (WebSocket upgrade, proxy failure)
 * so they share the wire shape of the JAX-RS exception-mapper output. Records
 * an {@code aussie.errors.total} counter tagged by service and stable problem code.
 */
@ApplicationScoped
public class ProxyErrorWriter {

    private static final Logger LOG = Logger.getLogger(ProxyErrorWriter.class);

    private static final CharSequence HEADER_RATELIMIT_LIMIT = HttpHeaders.createOptimized("X-RateLimit-Limit");
    private static final CharSequence HEADER_RATELIMIT_REMAINING = HttpHeaders.createOptimized("X-RateLimit-Remaining");
    private static final CharSequence HEADER_RATELIMIT_RESET = HttpHeaders.createOptimized("X-RateLimit-Reset");
    private static final CharSequence HEADER_VALUE_ZERO = HttpHeaders.createOptimized("0");

    private final GatewayMetrics metrics;

    @Inject
    public ProxyErrorWriter(GatewayMetrics metrics) {
        this.metrics = metrics;
    }

    /** Write a problem response and end the request. */
    public void write(RoutingContext ctx, ProblemDetail problem) {
        write(ctx, problem, null);
    }

    /**
     * Write a problem response and end the request. {@code serviceId} is used
     * as a metric tag; pass {@code null} when the service context is unknown
     * (e.g., route-not-found).
     */
    public void write(RoutingContext ctx, ProblemDetail problem, String serviceId) {
        final var response = ctx.response();
        if (!canWrite(response, ctx, problem)) {
            return;
        }
        recordMetric(serviceId, problem);
        logIfServerError(ctx, problem);
        response.setStatusCode(problem.status())
                .putHeader(HttpHeaders.CONTENT_TYPE, (CharSequence) ProblemJson.CONTENT_TYPE)
                .end(ProblemJson.serialize(problem, ctx.request().path()));
    }

    /**
     * Write a 429 rate-limit response with the full {@code Retry-After} and
     * {@code X-RateLimit-*} header set so existing clients that read those
     * headers continue to work.
     */
    public void writeRateLimit(
            RoutingContext ctx,
            ProblemDetail problem,
            String serviceId,
            long retryAfterSeconds,
            long limit,
            long resetAtEpochSeconds) {
        final var response = ctx.response();
        if (!canWrite(response, ctx, problem)) {
            return;
        }
        recordMetric(serviceId, problem);
        response.setStatusCode(problem.status())
                .putHeader(HttpHeaders.CONTENT_TYPE, (CharSequence) ProblemJson.CONTENT_TYPE)
                .putHeader(HttpHeaders.RETRY_AFTER, (CharSequence) Long.toString(retryAfterSeconds))
                .putHeader(HEADER_RATELIMIT_LIMIT, (CharSequence) Long.toString(limit))
                .putHeader(HEADER_RATELIMIT_REMAINING, HEADER_VALUE_ZERO)
                .putHeader(HEADER_RATELIMIT_RESET, (CharSequence) Long.toString(resetAtEpochSeconds))
                .end(ProblemJson.serialize(problem, ctx.request().path()));
    }

    private boolean canWrite(HttpServerResponse response, RoutingContext ctx, ProblemDetail problem) {
        if (response.ended() || response.headWritten()) {
            LOG.debugv(
                    "dropping problem response (already committed): status={0} path={1}",
                    problem.status(), ctx.request().uri());
            return false;
        }
        return true;
    }

    private void recordMetric(String serviceId, ProblemDetail problem) {
        if (metrics.isEnabled()) {
            metrics.recordError(serviceId != null ? serviceId : "unknown", problem.code());
        }
    }

    private void logIfServerError(RoutingContext ctx, ProblemDetail problem) {
        if (problem.status() >= 500) {
            LOG.warnv(
                    "problem response: status={0} title={1} path={2}",
                    problem.status(), problem.title(), ctx.request().uri());
        }
    }
}
