package aussie.adapter.out.telemetry;

import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jboss.logging.Logger;

import aussie.core.config.ResiliencyConfig;

/**
 * Exposes bulkhead metrics for connection pool monitoring.
 *
 * <p>Aussie's threading model uses connection pools as bulkheads for fault isolation:
 * <ul>
 *   <li><b>Cassandra</b>: Connection pool isolates database operations</li>
 *   <li><b>Redis</b>: Connection pool isolates cache/session operations</li>
 *   <li><b>HTTP Proxy</b>: Connection pool isolates upstream service calls</li>
 * </ul>
 *
 * <p>Metrics exposed:
 * <ul>
 *   <li>{@code aussie.bulkhead.cassandra.pool.max} - Configured max Cassandra connections</li>
 *   <li>{@code aussie.bulkhead.redis.pool.max} - Configured max Redis connections</li>
 *   <li>{@code aussie.bulkhead.http.pool.max} - Configured max HTTP proxy connections per host</li>
 *   <li>{@code aussie.bulkhead.jwks.pool.max} - Configured max JWKS fetch connections</li>
 * </ul>
 *
 * <p>Note: Actual pool usage metrics are provided by the respective drivers:
 * <ul>
 *   <li>Cassandra: Enable via {@code quarkus.cassandra.metrics.enabled=true}</li>
 *   <li>Redis: Available via Quarkus Redis extension metrics</li>
 *   <li>HTTP: Available via Vert.x metrics ({@code quarkus.micrometer.binder.vertx.enabled=true})</li>
 * </ul>
 *
 * @see <a href="https://resilience4j.readme.io/docs/bulkhead">Bulkhead Pattern</a>
 */
@ApplicationScoped
public class BulkheadMetrics implements MeterBinder {

    private static final Logger LOG = Logger.getLogger(BulkheadMetrics.class);

    private final TelemetryConfig telemetryConfig;
    private final ResiliencyConfig resiliencyConfig;
    private final AtomicBoolean registered = new AtomicBoolean(false);

    @Inject
    public BulkheadMetrics(TelemetryConfig telemetryConfig, ResiliencyConfig resiliencyConfig) {
        this.telemetryConfig = telemetryConfig;
        this.resiliencyConfig = resiliencyConfig;
    }

    /**
     * Returns whether bulkhead metrics are enabled.
     *
     * @return true if telemetry and metrics are both enabled
     */
    public boolean isEnabled() {
        return telemetryConfig.enabled() && telemetryConfig.metrics().enabled();
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (!isEnabled()) {
            LOG.debug("Bulkhead metrics disabled (telemetry not enabled)");
            return;
        }

        if (!registered.compareAndSet(false, true)) {
            LOG.debug("Bulkhead metrics already registered, skipping");
            return;
        }

        LOG.info("Registering bulkhead configuration metrics");

        final int cassandraPoolSize = resiliencyConfig.cassandra().poolLocalSize();
        final int cassandraMaxRequests = resiliencyConfig.cassandra().maxRequestsPerConnection();
        final int redisPoolSize = resiliencyConfig.redis().poolSize();
        final int redisPoolWaiting = resiliencyConfig.redis().poolWaiting();
        final int httpMaxPerHost = resiliencyConfig.http().maxConnectionsPerHost();
        final int httpMaxTotal = resiliencyConfig.http().maxConnections();
        final int jwksMax = resiliencyConfig.jwks().maxConnections();

        Gauge.builder("aussie.bulkhead.cassandra.pool.max", () -> cassandraPoolSize)
                .description("Maximum Cassandra connections per node in local datacenter")
                .tag("type", "connection_pool")
                .register(registry);

        Gauge.builder("aussie.bulkhead.cassandra.requests.max", () -> cassandraMaxRequests)
                .description("Maximum concurrent requests per Cassandra connection")
                .tag("type", "request_limit")
                .register(registry);

        Gauge.builder("aussie.bulkhead.redis.pool.max", () -> redisPoolSize)
                .description("Maximum Redis connections in pool")
                .tag("type", "connection_pool")
                .register(registry);

        Gauge.builder("aussie.bulkhead.redis.pool.waiting.max", () -> redisPoolWaiting)
                .description("Maximum requests waiting when Redis pool is exhausted")
                .tag("type", "queue_limit")
                .register(registry);

        Gauge.builder("aussie.bulkhead.http.pool.max.per_host", () -> httpMaxPerHost)
                .description("Maximum HTTP connections per upstream host")
                .tag("type", "connection_pool")
                .register(registry);

        Gauge.builder("aussie.bulkhead.http.pool.max.total", () -> httpMaxTotal)
                .description("Maximum total HTTP connections across all upstream hosts")
                .tag("type", "connection_pool")
                .register(registry);

        Gauge.builder("aussie.bulkhead.jwks.pool.max", () -> jwksMax)
                .description("Maximum concurrent JWKS fetch connections")
                .tag("type", "connection_pool")
                .register(registry);

        LOG.infov(
                "Bulkhead metrics registered - Cassandra: {0} conn/{1} req, Redis: {2}/{3}, HTTP: {4}/{5}, JWKS: {6}",
                cassandraPoolSize,
                cassandraMaxRequests,
                redisPoolSize,
                redisPoolWaiting,
                httpMaxPerHost,
                httpMaxTotal,
                jwksMax);
    }

    /** Returns the configured Cassandra connection pool size per local node. */
    public int getCassandraPoolSize() {
        return resiliencyConfig.cassandra().poolLocalSize();
    }

    /** Returns the configured Redis connection pool size. */
    public int getRedisPoolSize() {
        return resiliencyConfig.redis().poolSize();
    }

    /** Returns the configured maximum HTTP connections per upstream host. */
    public int getHttpMaxConnectionsPerHost() {
        return resiliencyConfig.http().maxConnectionsPerHost();
    }

    /** Returns the configured maximum concurrent JWKS fetch connections. */
    public int getJwksMaxConnections() {
        return resiliencyConfig.jwks().maxConnections();
    }
}
