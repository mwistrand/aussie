package aussie.adapter.in.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import aussie.adapter.out.telemetry.BulkheadMetrics;

/**
 * Health check for connection pool bulkheads.
 *
 * <p>Reports the configured bulkhead limits for each dependency:
 * <ul>
 *   <li><b>Cassandra</b>: Connection pool size and max requests per connection</li>
 *   <li><b>Redis</b>: Connection pool size and max waiting requests</li>
 *   <li><b>HTTP Proxy</b>: Max connections per host and total connections</li>
 *   <li><b>JWKS</b>: Max fetch connections</li>
 * </ul>
 *
 * <p>This health check always reports UP since configuration is validated at startup.
 *
 * <h2>Why Configuration Only?</h2>
 * <p>We intentionally avoid marking DOWN due to runtime pool pressure. Health checks
 * should be binary and stable - transient pool exhaustion under load is expected
 * and should trigger alerts via metrics, not mark the service as unhealthy. Use the
 * driver-provided usage metrics (e.g., {@code aussie.bulkhead.*.pool.max}) and set
 * alerts at 80-90% occupancy sustained for N minutes.
 *
 * <h2>Threading Model</h2>
 * <p>Aussie uses connection pools as bulkheads for fault isolation:
 * <ul>
 *   <li>Event loop (CPU-bound): All CPU work runs here, pinned to CPU cores</li>
 *   <li>IO pools (bulkheads): Each dependency has isolated connections</li>
 * </ul>
 *
 * <p>If Cassandra is slow, only Cassandra operations are affected.
 * Redis and HTTP proxy operations continue unimpacted.
 *
 * @see BulkheadMetrics
 */
@Readiness
@ApplicationScoped
public class BulkheadHealthCheck implements HealthCheck {

    private final BulkheadMetrics bulkheadMetrics;

    @Inject
    public BulkheadHealthCheck(BulkheadMetrics bulkheadMetrics) {
        this.bulkheadMetrics = bulkheadMetrics;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.builder().name("bulkheads");
        builder.withData("aussie.bulkhead.cassandra.pool.max", bulkheadMetrics.getCassandraPoolSize());
        builder.withData("aussie.bulkhead.redis.pool.max", bulkheadMetrics.getRedisPoolSize());
        builder.withData("aussie.bulkhead.http.pool.max.per_host", bulkheadMetrics.getHttpMaxConnectionsPerHost());
        builder.withData("aussie.bulkhead.jwks.pool.max", bulkheadMetrics.getJwksMaxConnections());
        builder.withData("metrics.enabled", bulkheadMetrics.isEnabled());

        // UP if configuration is loaded; pool exhaustion is monitored via metrics/alerts
        return builder.up().build();
    }
}
