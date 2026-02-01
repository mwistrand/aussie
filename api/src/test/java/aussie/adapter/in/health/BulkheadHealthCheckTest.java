package aussie.adapter.in.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.telemetry.BulkheadMetrics;

@DisplayName("BulkheadHealthCheck")
class BulkheadHealthCheckTest {

    private BulkheadMetrics bulkheadMetrics;
    private BulkheadHealthCheck healthCheck;

    @BeforeEach
    void setUp() {
        bulkheadMetrics = mock(BulkheadMetrics.class);
        when(bulkheadMetrics.getCassandraPoolSize()).thenReturn(40);
        when(bulkheadMetrics.getRedisPoolSize()).thenReturn(50);
        when(bulkheadMetrics.getHttpMaxConnectionsPerHost()).thenReturn(75);
        when(bulkheadMetrics.getJwksMaxConnections()).thenReturn(15);
        when(bulkheadMetrics.isEnabled()).thenReturn(true);

        healthCheck = new BulkheadHealthCheck(bulkheadMetrics);
    }

    @Test
    @DisplayName("should return UP status")
    void shouldReturnUpStatus() {
        HealthCheckResponse response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    }

    @Test
    @DisplayName("should have correct health check name")
    void shouldHaveCorrectName() {
        HealthCheckResponse response = healthCheck.call();

        assertEquals("bulkheads", response.getName());
    }

    @Test
    @DisplayName("should include all bulkhead configuration data")
    void shouldIncludeAllBulkheadData() {
        HealthCheckResponse response = healthCheck.call();

        assertTrue(response.getData().isPresent());
        var data = response.getData().get();

        assertEquals(40L, data.get("aussie.bulkhead.cassandra.pool.max"));
        assertEquals(50L, data.get("aussie.bulkhead.redis.pool.max"));
        assertEquals(75L, data.get("aussie.bulkhead.http.pool.max.per_host"));
        assertEquals(15L, data.get("aussie.bulkhead.jwks.pool.max"));
        assertEquals(true, data.get("metrics.enabled"));
    }

    @Test
    @DisplayName("should report metrics.enabled as false when disabled")
    void shouldReportMetricsDisabled() {
        when(bulkheadMetrics.isEnabled()).thenReturn(false);

        HealthCheckResponse response = healthCheck.call();

        assertTrue(response.getData().isPresent());
        assertEquals(false, response.getData().get().get("metrics.enabled"));
    }
}
