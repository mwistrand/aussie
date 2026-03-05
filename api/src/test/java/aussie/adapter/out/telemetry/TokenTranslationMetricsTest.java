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

import aussie.core.model.auth.TranslationOutcome;

@DisplayName("TokenTranslationMetrics")
class TokenTranslationMetricsTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private TelemetryConfig enabledConfig() {
        var metricsConfig = mock(TelemetryConfig.MetricsConfig.class);
        when(metricsConfig.enabled()).thenReturn(true);
        var config = mock(TelemetryConfig.class);
        when(config.enabled()).thenReturn(true);
        when(config.metrics()).thenReturn(metricsConfig);
        return config;
    }

    private TelemetryConfig disabledConfig() {
        var metricsConfig = mock(TelemetryConfig.MetricsConfig.class);
        when(metricsConfig.enabled()).thenReturn(false);
        var config = mock(TelemetryConfig.class);
        when(config.enabled()).thenReturn(false);
        when(config.metrics()).thenReturn(metricsConfig);
        return config;
    }

    @Nested
    @DisplayName("DisabledMetrics")
    class DisabledMetrics {

        private TokenTranslationMetrics metrics;

        @BeforeEach
        void setUp() {
            metrics = new TokenTranslationMetrics(registry, disabledConfig());
            metrics.init();
        }

        @Test
        @DisplayName("isEnabled returns false when config is disabled")
        void isEnabledReturnsFalse() {
            assertFalse(metrics.isEnabled());
        }

        @Test
        @DisplayName("recordTranslation is a no-op when disabled")
        void recordTranslationNoOp() {
            metrics.recordTranslation("okta", TranslationOutcome.SUCCESS, 50);
            assertNull(registry.find("aussie.token.translation.total").counter());
        }

        @Test
        @DisplayName("recordCacheHit is a no-op when disabled")
        void recordCacheHitNoOp() {
            metrics.recordCacheHit();
            assertNull(registry.find("aussie.token.translation.cache.hits").counter());
        }

        @Test
        @DisplayName("recordCacheMiss is a no-op when disabled")
        void recordCacheMissNoOp() {
            metrics.recordCacheMiss();
            assertNull(registry.find("aussie.token.translation.cache.misses").counter());
        }

        @Test
        @DisplayName("recordError is a no-op when disabled")
        void recordErrorNoOp() {
            metrics.recordError("okta", "timeout");
            assertNull(registry.find("aussie.token.translation.errors").counter());
        }

        @Test
        @DisplayName("recordRemoteCall is a no-op when disabled")
        void recordRemoteCallNoOp() {
            metrics.recordRemoteCall(200, 100);
            assertNull(registry.find("aussie.token.translation.remote.total").counter());
        }

        @Test
        @DisplayName("recordConfigReload is a no-op when disabled")
        void recordConfigReloadNoOp() {
            metrics.recordConfigReload(true);
            assertNull(registry.find("aussie.token.translation.config.reloads").counter());
        }

        @Test
        @DisplayName("cache size gauge is not registered when disabled")
        void cacheSizeGaugeNotRegistered() {
            assertNull(registry.find("aussie.token.translation.cache.size").gauge());
        }
    }

    @Nested
    @DisplayName("EnabledMetrics")
    class EnabledMetrics {

        private TokenTranslationMetrics metrics;

        @BeforeEach
        void setUp() {
            metrics = new TokenTranslationMetrics(registry, enabledConfig());
            metrics.init();
        }

        @Test
        @DisplayName("isEnabled returns true when config is enabled")
        void isEnabledReturnsTrue() {
            assertTrue(metrics.isEnabled());
        }

        @Test
        @DisplayName("recordTranslation increments counter and records timer for SUCCESS")
        void recordTranslationSuccess() {
            metrics.recordTranslation("okta", TranslationOutcome.SUCCESS, 50);

            var counter = registry.find("aussie.token.translation.total")
                    .tag("provider", "okta")
                    .tag("outcome", "success")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());

            var timer = registry.find("aussie.token.translation.duration")
                    .tag("provider", "okta")
                    .tag("outcome", "success")
                    .timer();
            assertNotNull(timer);
            assertEquals(1, timer.count());
        }

        @Test
        @DisplayName("recordTranslation records ERROR outcome")
        void recordTranslationError() {
            metrics.recordTranslation("auth0", TranslationOutcome.ERROR, 100);

            var counter = registry.find("aussie.token.translation.total")
                    .tag("provider", "auth0")
                    .tag("outcome", "error")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordTranslation records FALLBACK outcome")
        void recordTranslationFallback() {
            metrics.recordTranslation("okta", TranslationOutcome.FALLBACK, 75);

            var counter = registry.find("aussie.token.translation.total")
                    .tag("outcome", "fallback")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordTranslation records EMPTY outcome")
        void recordTranslationEmpty() {
            metrics.recordTranslation("okta", TranslationOutcome.EMPTY, 10);

            var counter = registry.find("aussie.token.translation.total")
                    .tag("outcome", "empty")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordTranslation uses 'unknown' for null provider")
        void recordTranslationNullProvider() {
            metrics.recordTranslation(null, TranslationOutcome.SUCCESS, 50);

            var counter = registry.find("aussie.token.translation.total")
                    .tag("provider", "unknown")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordCacheHit increments cache hit counter")
        void recordCacheHit() {
            metrics.recordCacheHit();
            metrics.recordCacheHit();

            var counter = registry.find("aussie.token.translation.cache.hits").counter();
            assertNotNull(counter);
            assertEquals(2.0, counter.count());
        }

        @Test
        @DisplayName("recordCacheMiss increments cache miss counter")
        void recordCacheMiss() {
            metrics.recordCacheMiss();

            var counter = registry.find("aussie.token.translation.cache.misses").counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("updateCacheSize updates gauge value")
        void updateCacheSize() {
            metrics.updateCacheSize(42);

            var gauge = registry.find("aussie.token.translation.cache.size").gauge();
            assertNotNull(gauge);
            assertEquals(42.0, gauge.value());
        }

        @Test
        @DisplayName("recordError increments error counter with correct tags")
        void recordError() {
            metrics.recordError("okta", "timeout");

            var counter = registry.find("aussie.token.translation.errors")
                    .tag("provider", "okta")
                    .tag("error_type", "timeout")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordError uses 'unknown' for null values")
        void recordErrorNullValues() {
            metrics.recordError(null, null);

            var counter = registry.find("aussie.token.translation.errors")
                    .tag("provider", "unknown")
                    .tag("error_type", "unknown")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordRemoteCall increments counter and records timer")
        void recordRemoteCall() {
            metrics.recordRemoteCall(200, 100);

            var counter = registry.find("aussie.token.translation.remote.total")
                    .tag("status", "200")
                    .tag("status_class", "2xx")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());

            var timer = registry.find("aussie.token.translation.remote.duration")
                    .tag("status_class", "2xx")
                    .timer();
            assertNotNull(timer);
            assertEquals(1, timer.count());
        }

        @Test
        @DisplayName("recordRemoteCall maps status classes correctly")
        void recordRemoteCallStatusClasses() {
            metrics.recordRemoteCall(500, 50);

            var counter = registry.find("aussie.token.translation.remote.total")
                    .tag("status_class", "5xx")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordConfigReload records successful reload")
        void recordConfigReloadSuccess() {
            metrics.recordConfigReload(true);

            var counter = registry.find("aussie.token.translation.config.reloads")
                    .tag("success", "true")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("recordConfigReload records failed reload")
        void recordConfigReloadFailure() {
            metrics.recordConfigReload(false);

            var counter = registry.find("aussie.token.translation.config.reloads")
                    .tag("success", "false")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }
    }
}
