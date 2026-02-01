package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.ResiliencyConfig;

@DisplayName("BulkheadMetrics")
class BulkheadMetricsTest {

    private TelemetryConfig telemetryConfig;
    private TelemetryConfig.MetricsConfig metricsConfig;
    private ResiliencyConfig resiliencyConfig;
    private ResiliencyConfig.CassandraConfig cassandraConfig;
    private ResiliencyConfig.RedisConfig redisConfig;
    private ResiliencyConfig.HttpConfig httpConfig;
    private ResiliencyConfig.JwksConfig jwksConfig;

    @BeforeEach
    void setUp() {
        telemetryConfig = mock(TelemetryConfig.class);
        metricsConfig = mock(TelemetryConfig.MetricsConfig.class);
        when(telemetryConfig.metrics()).thenReturn(metricsConfig);

        resiliencyConfig = mock(ResiliencyConfig.class);
        cassandraConfig = mock(ResiliencyConfig.CassandraConfig.class);
        redisConfig = mock(ResiliencyConfig.RedisConfig.class);
        httpConfig = mock(ResiliencyConfig.HttpConfig.class);
        jwksConfig = mock(ResiliencyConfig.JwksConfig.class);

        when(resiliencyConfig.cassandra()).thenReturn(cassandraConfig);
        when(resiliencyConfig.redis()).thenReturn(redisConfig);
        when(resiliencyConfig.http()).thenReturn(httpConfig);
        when(resiliencyConfig.jwks()).thenReturn(jwksConfig);
    }

    private void setupDefaultPoolSizes() {
        when(cassandraConfig.poolLocalSize()).thenReturn(30);
        when(cassandraConfig.maxRequestsPerConnection()).thenReturn(1024);
        when(redisConfig.poolSize()).thenReturn(30);
        when(redisConfig.poolWaiting()).thenReturn(100);
        when(httpConfig.maxConnectionsPerHost()).thenReturn(50);
        when(httpConfig.maxConnections()).thenReturn(200);
        when(jwksConfig.maxConnections()).thenReturn(10);
    }

    @Nested
    @DisplayName("isEnabled")
    class IsEnabled {

        @Test
        @DisplayName("should return true when telemetry and metrics are enabled")
        void shouldReturnTrueWhenBothEnabled() {
            when(telemetryConfig.enabled()).thenReturn(true);
            when(metricsConfig.enabled()).thenReturn(true);
            setupDefaultPoolSizes();

            var metrics = new BulkheadMetrics(telemetryConfig, resiliencyConfig);

            assertTrue(metrics.isEnabled());
        }

        @Test
        @DisplayName("should return false when telemetry is disabled")
        void shouldReturnFalseWhenTelemetryDisabled() {
            when(telemetryConfig.enabled()).thenReturn(false);
            when(metricsConfig.enabled()).thenReturn(true);
            setupDefaultPoolSizes();

            var metrics = new BulkheadMetrics(telemetryConfig, resiliencyConfig);

            assertFalse(metrics.isEnabled());
        }

        @Test
        @DisplayName("should return false when metrics are disabled")
        void shouldReturnFalseWhenMetricsDisabled() {
            when(telemetryConfig.enabled()).thenReturn(true);
            when(metricsConfig.enabled()).thenReturn(false);
            setupDefaultPoolSizes();

            var metrics = new BulkheadMetrics(telemetryConfig, resiliencyConfig);

            assertFalse(metrics.isEnabled());
        }
    }

    @Nested
    @DisplayName("bindTo")
    class BindTo {

        @Test
        @DisplayName("should not register gauges when disabled")
        void shouldNotRegisterWhenDisabled() {
            when(telemetryConfig.enabled()).thenReturn(false);
            when(metricsConfig.enabled()).thenReturn(false);
            setupDefaultPoolSizes();

            var metrics = new BulkheadMetrics(telemetryConfig, resiliencyConfig);
            MeterRegistry registry = new SimpleMeterRegistry();

            metrics.bindTo(registry);

            assertEquals(0, registry.getMeters().size());
        }

        @Test
        @DisplayName("should register all gauges when enabled")
        void shouldRegisterAllGaugesWhenEnabled() {
            when(telemetryConfig.enabled()).thenReturn(true);
            when(metricsConfig.enabled()).thenReturn(true);

            when(cassandraConfig.poolLocalSize()).thenReturn(40);
            when(cassandraConfig.maxRequestsPerConnection()).thenReturn(2048);
            when(redisConfig.poolSize()).thenReturn(50);
            when(redisConfig.poolWaiting()).thenReturn(150);
            when(httpConfig.maxConnectionsPerHost()).thenReturn(75);
            when(httpConfig.maxConnections()).thenReturn(300);
            when(jwksConfig.maxConnections()).thenReturn(15);

            var metrics = new BulkheadMetrics(telemetryConfig, resiliencyConfig);
            MeterRegistry registry = new SimpleMeterRegistry();

            metrics.bindTo(registry);

            Gauge cassandraPool =
                    registry.find("aussie.bulkhead.cassandra.pool.max").gauge();
            assertEquals(40.0, cassandraPool.value());

            Gauge cassandraRequests =
                    registry.find("aussie.bulkhead.cassandra.requests.max").gauge();
            assertEquals(2048.0, cassandraRequests.value());

            Gauge redisPool = registry.find("aussie.bulkhead.redis.pool.max").gauge();
            assertEquals(50.0, redisPool.value());

            Gauge redisWaiting =
                    registry.find("aussie.bulkhead.redis.pool.waiting.max").gauge();
            assertEquals(150.0, redisWaiting.value());

            Gauge httpPerHost =
                    registry.find("aussie.bulkhead.http.pool.max.per_host").gauge();
            assertEquals(75.0, httpPerHost.value());

            Gauge httpTotal =
                    registry.find("aussie.bulkhead.http.pool.max.total").gauge();
            assertEquals(300.0, httpTotal.value());

            Gauge jwks = registry.find("aussie.bulkhead.jwks.pool.max").gauge();
            assertEquals(15.0, jwks.value());
        }

        @Test
        @DisplayName("should only register once on multiple calls")
        void shouldOnlyRegisterOnce() {
            when(telemetryConfig.enabled()).thenReturn(true);
            when(metricsConfig.enabled()).thenReturn(true);
            setupDefaultPoolSizes();

            var metrics = new BulkheadMetrics(telemetryConfig, resiliencyConfig);
            MeterRegistry registry = new SimpleMeterRegistry();

            metrics.bindTo(registry);
            int firstCount = registry.getMeters().size();

            metrics.bindTo(registry);
            int secondCount = registry.getMeters().size();

            assertEquals(firstCount, secondCount);
        }
    }

    @Nested
    @DisplayName("accessor methods")
    class AccessorMethods {

        @Test
        @DisplayName("should return configured pool sizes")
        void shouldReturnConfiguredPoolSizes() {
            when(telemetryConfig.enabled()).thenReturn(true);
            when(metricsConfig.enabled()).thenReturn(true);

            when(cassandraConfig.poolLocalSize()).thenReturn(40);
            when(redisConfig.poolSize()).thenReturn(50);
            when(httpConfig.maxConnectionsPerHost()).thenReturn(75);
            when(jwksConfig.maxConnections()).thenReturn(15);

            var metrics = new BulkheadMetrics(telemetryConfig, resiliencyConfig);

            assertEquals(40, metrics.getCassandraPoolSize());
            assertEquals(50, metrics.getRedisPoolSize());
            assertEquals(75, metrics.getHttpMaxConnectionsPerHost());
            assertEquals(15, metrics.getJwksMaxConnections());
        }
    }
}
