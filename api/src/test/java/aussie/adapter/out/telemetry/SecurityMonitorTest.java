package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import aussie.spi.SecurityEvent;

@DisplayName("SecurityMonitor")
class SecurityMonitorTest {

    private TelemetryConfig config;
    private TelemetryConfig.SecurityConfig securityConfig;
    private TelemetryConfig.SecurityConfig.DosDetectionConfig dosConfig;
    private SecurityEventDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        config = mock(TelemetryConfig.class);
        securityConfig = mock(TelemetryConfig.SecurityConfig.class);
        dosConfig = mock(TelemetryConfig.SecurityConfig.DosDetectionConfig.class);
        dispatcher = mock(SecurityEventDispatcher.class);

        when(config.enabled()).thenReturn(true);
        when(config.security()).thenReturn(securityConfig);
        when(securityConfig.enabled()).thenReturn(true);
        when(securityConfig.rateLimitWindow()).thenReturn(Duration.ofMinutes(1));
        when(securityConfig.rateLimitThreshold()).thenReturn(100);
        when(securityConfig.dosDetection()).thenReturn(dosConfig);
        when(dosConfig.enabled()).thenReturn(false);
    }

    private SecurityMonitor createEnabled() {
        return new SecurityMonitor(config, dispatcher);
    }

    private SecurityMonitor createDisabled() {
        when(config.enabled()).thenReturn(false);
        return new SecurityMonitor(config, dispatcher);
    }

    @Nested
    @DisplayName("isEnabled()")
    class IsEnabledTests {

        @Test
        @DisplayName("should return true when telemetry and security enabled")
        void shouldReturnTrueWhenEnabled() {
            assertTrue(createEnabled().isEnabled());
        }

        @Test
        @DisplayName("should return false when security monitoring disabled")
        void shouldReturnFalseWhenDisabled() {
            when(securityConfig.enabled()).thenReturn(false);
            assertFalse(new SecurityMonitor(config, dispatcher).isEnabled());
        }
    }

    @Nested
    @DisplayName("recordRequest()")
    class RecordRequestTests {

        @Test
        @DisplayName("should not dispatch when disabled")
        void shouldNotDispatchWhenDisabled() {
            var monitor = createDisabled();
            monitor.recordRequest("1.2.3.4", "svc-1", false);
            verify(dispatcher, never()).dispatch(any());
        }

        @Test
        @DisplayName("should not dispatch for normal request counts")
        void shouldNotDispatchForNormalRequests() {
            var monitor = createEnabled();
            monitor.recordRequest("1.2.3.4", "svc-1", false);
            verify(dispatcher, never()).dispatch(any());
        }

        @Test
        @DisplayName("should dispatch rate limit event when threshold exceeded")
        void shouldDispatchRateLimitEvent() {
            when(securityConfig.rateLimitThreshold()).thenReturn(2);
            var monitor = createEnabled();

            monitor.recordRequest("1.2.3.4", "svc-1", false);
            monitor.recordRequest("1.2.3.4", "svc-1", false);
            monitor.recordRequest("1.2.3.4", "svc-1", false);

            var captor = ArgumentCaptor.forClass(SecurityEvent.class);
            verify(dispatcher).dispatch(captor.capture());
            assertTrue(captor.getValue() instanceof SecurityEvent.RateLimitExceeded);
        }

        @Test
        @DisplayName("should track errors separately")
        void shouldTrackErrors() {
            var monitor = createEnabled();
            monitor.recordRequest("1.2.3.4", "svc-1", true);
        }
    }

    @Nested
    @DisplayName("recordAuthFailure()")
    class RecordAuthFailureTests {

        @Test
        @DisplayName("should not dispatch when disabled")
        void shouldNotDispatchWhenDisabled() {
            var monitor = createDisabled();
            monitor.recordAuthFailure("1.2.3.4", "invalid_key", "api_key");
            verify(dispatcher, never()).dispatch(any());
        }

        @Test
        @DisplayName("should dispatch authentication failure event")
        void shouldDispatchAuthFailureEvent() {
            var monitor = createEnabled();
            monitor.recordAuthFailure("1.2.3.4", "invalid_key", "api_key");

            var captor = ArgumentCaptor.forClass(SecurityEvent.class);
            verify(dispatcher).dispatch(captor.capture());
            var event = (SecurityEvent.AuthenticationFailure) captor.getValue();
            assertEquals("invalid_key", event.reason());
            assertEquals("api_key", event.attemptedMethod());
            assertEquals(1, event.failureCount());
        }

        @Test
        @DisplayName("should dispatch suspicious pattern after 5 failures")
        void shouldDispatchSuspiciousPatternAfterThreshold() {
            var monitor = createEnabled();
            for (int i = 0; i < 5; i++) {
                monitor.recordAuthFailure("1.2.3.4", "invalid_key", "api_key");
            }

            var captor = ArgumentCaptor.forClass(SecurityEvent.class);
            verify(dispatcher, times(6)).dispatch(captor.capture());

            var events = captor.getAllValues();
            boolean hasSuspicious = events.stream()
                    .anyMatch(e -> e instanceof SecurityEvent.SuspiciousPattern sp
                            && "brute_force_attempt".equals(sp.patternType()));
            assertTrue(hasSuspicious);
        }
    }

    @Nested
    @DisplayName("recordAccessDenied()")
    class RecordAccessDeniedTests {

        @Test
        @DisplayName("should dispatch access denied event")
        void shouldDispatchAccessDeniedEvent() {
            var monitor = createEnabled();
            monitor.recordAccessDenied("1.2.3.4", "svc-1", "/admin", "ip_blocked");

            var captor = ArgumentCaptor.forClass(SecurityEvent.class);
            verify(dispatcher).dispatch(captor.capture());
            var event = (SecurityEvent.AccessDenied) captor.getValue();
            assertEquals("svc-1", event.serviceId());
            assertEquals("/admin", event.path());
            assertEquals("ip_blocked", event.reason());
        }

        @Test
        @DisplayName("should not dispatch when disabled")
        void shouldNotDispatchWhenDisabled() {
            var monitor = createDisabled();
            monitor.recordAccessDenied("1.2.3.4", "svc-1", "/admin", "ip_blocked");
            verify(dispatcher, never()).dispatch(any());
        }
    }

    @Nested
    @DisplayName("recordSessionInvalidation()")
    class RecordSessionInvalidationTests {

        @Test
        @DisplayName("should dispatch session invalidated event")
        void shouldDispatchSessionInvalidatedEvent() {
            var monitor = createEnabled();
            monitor.recordSessionInvalidation("1.2.3.4", "session-123", "user-1", "logout");

            var captor = ArgumentCaptor.forClass(SecurityEvent.class);
            verify(dispatcher).dispatch(captor.capture());
            assertTrue(captor.getValue() instanceof SecurityEvent.SessionInvalidated);
        }

        @Test
        @DisplayName("should not dispatch when disabled")
        void shouldNotDispatchWhenDisabled() {
            var monitor = createDisabled();
            monitor.recordSessionInvalidation("1.2.3.4", "session-123", "user-1", "logout");
            verify(dispatcher, never()).dispatch(any());
        }
    }

    @Nested
    @DisplayName("resetClient()")
    class ResetClientTests {

        @Test
        @DisplayName("should clear tracking data for client")
        void shouldClearTrackingData() {
            var monitor = createEnabled();
            monitor.recordAuthFailure("1.2.3.4", "invalid_key", "api_key");
            monitor.resetClient("1.2.3.4");

            monitor.recordAuthFailure("1.2.3.4", "invalid_key", "api_key");
            var captor = ArgumentCaptor.forClass(SecurityEvent.class);
            verify(dispatcher, times(2)).dispatch(captor.capture());
            var lastEvent =
                    (SecurityEvent.AuthenticationFailure) captor.getAllValues().get(1);
            assertEquals(1, lastEvent.failureCount());
        }
    }

    @Nested
    @DisplayName("DoS detection")
    class DosDetectionTests {

        @Test
        @DisplayName("should detect request flood when spike threshold exceeded")
        void shouldDetectRequestFlood() {
            when(securityConfig.rateLimitThreshold()).thenReturn(2);
            when(dosConfig.enabled()).thenReturn(true);
            when(dosConfig.spikeThreshold()).thenReturn(2.0);
            when(dosConfig.errorRateThreshold()).thenReturn(0.5);

            var monitor = createEnabled();
            for (int i = 0; i < 5; i++) {
                monitor.recordRequest("1.2.3.4", "svc-1", false);
            }

            var captor = ArgumentCaptor.forClass(SecurityEvent.class);
            verify(dispatcher, times(4)).dispatch(captor.capture());
            boolean hasDoS = captor.getAllValues().stream().anyMatch(e -> e instanceof SecurityEvent.DosAttackDetected);
            assertTrue(hasDoS);
        }

        @Test
        @DisplayName("should detect high error rate")
        void shouldDetectHighErrorRate() {
            when(securityConfig.rateLimitThreshold()).thenReturn(2);
            when(dosConfig.enabled()).thenReturn(true);
            when(dosConfig.spikeThreshold()).thenReturn(100.0); // high so DoS doesn't trigger
            when(dosConfig.errorRateThreshold()).thenReturn(0.5);

            var monitor = createEnabled();
            // 3 requests, all errors → error rate = 1.0 > 0.5
            for (int i = 0; i < 3; i++) {
                monitor.recordRequest("1.2.3.4", "svc-1", true);
            }

            var captor = ArgumentCaptor.forClass(SecurityEvent.class);
            verify(dispatcher, org.mockito.Mockito.atLeast(2)).dispatch(captor.capture());
            boolean hasSuspicious = captor.getAllValues().stream()
                    .anyMatch(e -> e instanceof SecurityEvent.SuspiciousPattern sp
                            && "high_error_rate".equals(sp.patternType()));
            assertTrue(hasSuspicious);
        }
    }

    @Nested
    @DisplayName("hashIp()")
    class HashIpTests {

        @Test
        @DisplayName("should handle null IP")
        void shouldHandleNullIp() {
            var monitor = createEnabled();
            monitor.recordRequest(null, "svc-1", false);
        }
    }

    @Nested
    @DisplayName("SlidingWindowCounter")
    class SlidingWindowCounterTests {

        @Test
        @DisplayName("should increment and return count")
        void shouldIncrementAndReturnCount() {
            var counter = new SecurityMonitor.SlidingWindowCounter(Duration.ofMinutes(1));
            counter.increment();
            counter.increment();
            counter.increment();
            assertEquals(3, counter.getCount());
        }

        @Test
        @DisplayName("should return 0 for stale window")
        void shouldReturnZeroForStaleWindow() throws InterruptedException {
            var counter = new SecurityMonitor.SlidingWindowCounter(Duration.ofMillis(1));
            counter.increment();
            Thread.sleep(10);
            assertEquals(0, counter.getCount());
        }
    }
}
