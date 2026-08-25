package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("TrafficAttributionService")
class TrafficAttributionServiceTest {

    private SimpleMeterRegistry registry;
    private TelemetryConfig config;
    private TelemetryConfig.AttributionConfig attributionConfig;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        config = mock(TelemetryConfig.class);
        attributionConfig = mock(TelemetryConfig.AttributionConfig.class);
        when(config.attribution()).thenReturn(attributionConfig);
    }

    private TrafficAttributionService createEnabled() {
        when(config.enabled()).thenReturn(true);
        when(attributionConfig.enabled()).thenReturn(true);
        return new TrafficAttributionService(registry, config);
    }

    private TrafficAttributionService createDisabled() {
        when(config.enabled()).thenReturn(false);
        when(attributionConfig.enabled()).thenReturn(false);
        return new TrafficAttributionService(registry, config);
    }

    @Nested
    @DisplayName("isEnabled()")
    class IsEnabledTests {

        @Test
        @DisplayName("should return true when telemetry and attribution enabled")
        void shouldReturnTrueWhenEnabled() {
            assertTrue(createEnabled().isEnabled());
        }

        @Test
        @DisplayName("should return false when telemetry disabled")
        void shouldReturnFalseWhenTelemetryDisabled() {
            when(config.enabled()).thenReturn(false);
            when(attributionConfig.enabled()).thenReturn(true);
            assertFalse(new TrafficAttributionService(registry, config).isEnabled());
        }

        @Test
        @DisplayName("should return false when attribution disabled")
        void shouldReturnFalseWhenAttributionDisabled() {
            when(config.enabled()).thenReturn(true);
            when(attributionConfig.enabled()).thenReturn(false);
            assertFalse(new TrafficAttributionService(registry, config).isEnabled());
        }

        @Test
        @DisplayName("should return false when config is null")
        void shouldReturnFalseWhenConfigNull() {
            assertFalse(new TrafficAttributionService(registry, null).isEnabled());
        }
    }

    @Nested
    @DisplayName("getConfig()")
    class GetConfigTests {

        @Test
        @DisplayName("should return attribution config")
        void shouldReturnAttributionConfig() {
            var service = createEnabled();
            assertEquals(attributionConfig, service.getConfig());
        }
    }

    @Nested
    @DisplayName("recordAttributedRequest()")
    class RecordAttributedRequestTests {

        @Test
        @DisplayName("should record all metrics when enabled")
        void shouldRecordMetrics() {
            var service = createEnabled();
            var attribution = new TrafficAttribution("svc-1", "team-1", "tenant-1", "app-1", "prod");
            var metrics = new TrafficAttributionService.RequestMetrics(1024, 2048, 150);

            service.recordAttributedRequest(attribution, metrics);

            var requestCounter = registry.find("aussie.attributed.requests.total")
                    .tag("service_id", "svc-1")
                    .tag("team_id", "team-1")
                    .counter();
            assertNotNull(requestCounter);
            assertEquals(1.0, requestCounter.count());
            assertNull(registry.find("aussie.attributed.requests.total")
                    .tag("tenant_id", "tenant-1")
                    .counter());

            var ingressCounter = registry.find("aussie.attributed.bytes.ingress")
                    .tag("service_id", "svc-1")
                    .counter();
            assertNotNull(ingressCounter);
            assertEquals(1024.0, ingressCounter.count());

            var egressCounter = registry.find("aussie.attributed.bytes.egress")
                    .tag("service_id", "svc-1")
                    .counter();
            assertNotNull(egressCounter);
            assertEquals(2048.0, egressCounter.count());

            var computeCounter = registry.find("aussie.attributed.compute.units")
                    .tag("service_id", "svc-1")
                    .counter();
            assertNotNull(computeCounter);
            assertTrue(computeCounter.count() > 2.0);

            var timer = registry.find("aussie.attributed.duration")
                    .tag("service_id", "svc-1")
                    .timer();
            assertNotNull(timer);
            assertEquals(1, timer.count());
        }

        @Test
        @DisplayName("should skip recording when disabled")
        void shouldSkipWhenDisabled() {
            var service = createDisabled();
            var attribution = new TrafficAttribution("svc-1", "team-1", null, null, null);
            var metrics = new TrafficAttributionService.RequestMetrics(100, 200, 50);

            service.recordAttributedRequest(attribution, metrics);

            var counter = registry.find("aussie.attributed.requests.total").counter();
            assertNull(counter);
        }

        @Test
        @DisplayName("should use 'unknown' for null attribution dimensions")
        void shouldUseUnknownForNullDimensions() {
            var service = createEnabled();
            var attribution = new TrafficAttribution(null, null, null, null, null);
            var metrics = new TrafficAttributionService.RequestMetrics(0, 0, 0);

            service.recordAttributedRequest(attribution, metrics);

            var counter = registry.find("aussie.attributed.requests.total")
                    .tag("service_id", "unknown")
                    .tag("team_id", "unknown")
                    .tag("environment", "unknown")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }
    }
}
