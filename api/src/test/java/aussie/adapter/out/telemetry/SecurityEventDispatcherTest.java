package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.spi.SecurityEvent;

@DisplayName("SecurityEventDispatcher")
class SecurityEventDispatcherTest {

    private TelemetryConfig config;
    private TelemetryConfig.SecurityConfig securityConfig;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        config = mock(TelemetryConfig.class);
        securityConfig = mock(TelemetryConfig.SecurityConfig.class);
        registry = new SimpleMeterRegistry();

        when(config.security()).thenReturn(securityConfig);
    }

    @Nested
    @DisplayName("isEnabled()")
    class IsEnabledTests {

        @Test
        @DisplayName("should return true when telemetry and security enabled")
        void shouldReturnTrueWhenEnabled() {
            when(config.enabled()).thenReturn(true);
            when(securityConfig.enabled()).thenReturn(true);

            var dispatcher = new SecurityEventDispatcher(config, registry);
            assertTrue(dispatcher.isEnabled());
        }

        @Test
        @DisplayName("should return false when telemetry disabled")
        void shouldReturnFalseWhenTelemetryDisabled() {
            when(config.enabled()).thenReturn(false);
            when(securityConfig.enabled()).thenReturn(true);

            var dispatcher = new SecurityEventDispatcher(config, registry);
            assertFalse(dispatcher.isEnabled());
        }

        @Test
        @DisplayName("should return false when security monitoring disabled")
        void shouldReturnFalseWhenSecurityDisabled() {
            when(config.enabled()).thenReturn(true);
            when(securityConfig.enabled()).thenReturn(false);

            var dispatcher = new SecurityEventDispatcher(config, registry);
            assertFalse(dispatcher.isEnabled());
        }
    }

    @Nested
    @DisplayName("dispatch()")
    class DispatchTests {

        @Test
        @DisplayName("should not throw when disabled")
        void shouldNotThrowWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            when(securityConfig.enabled()).thenReturn(false);

            var dispatcher = new SecurityEventDispatcher(config, registry);
            var event = new SecurityEvent.AuthenticationFailure(Instant.now(), "client-1", "invalid_key", "api_key", 1);
            assertDoesNotThrow(() -> dispatcher.dispatch(event));
        }

        @Test
        @DisplayName("should dispatch event when enabled and initialized")
        void shouldDispatchWhenEnabled() {
            when(config.enabled()).thenReturn(true);
            when(securityConfig.enabled()).thenReturn(true);

            var dispatcher = new SecurityEventDispatcher(config, registry);
            dispatcher.init();

            var event = new SecurityEvent.DosAttackDetected(Instant.now(), "client-1", "flood", Map.of("count", 1000));
            assertDoesNotThrow(() -> dispatcher.dispatch(event));
            dispatcher.shutdown();
        }
    }

    @Nested
    @DisplayName("getHandlers()")
    class GetHandlersTests {

        @Test
        @DisplayName("should return empty list when not initialized")
        void shouldReturnEmptyWhenNotInitialized() {
            when(config.enabled()).thenReturn(false);
            when(securityConfig.enabled()).thenReturn(false);

            var dispatcher = new SecurityEventDispatcher(config, registry);
            assertTrue(dispatcher.getHandlers().isEmpty());
        }
    }

    @Nested
    @DisplayName("init()")
    class InitTests {

        @Test
        @DisplayName("should skip initialization when disabled")
        void shouldSkipWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            when(securityConfig.enabled()).thenReturn(false);

            var dispatcher = new SecurityEventDispatcher(config, registry);
            dispatcher.init();
            assertTrue(dispatcher.getHandlers().isEmpty());
        }
    }

    @Nested
    @DisplayName("shutdown()")
    class ShutdownTests {

        @Test
        @DisplayName("should handle shutdown when not initialized")
        void shouldHandleShutdownWhenNotInitialized() {
            when(config.enabled()).thenReturn(false);
            when(securityConfig.enabled()).thenReturn(false);

            var dispatcher = new SecurityEventDispatcher(config, registry);
            assertDoesNotThrow(dispatcher::shutdown);
        }

        @Test
        @DisplayName("should shutdown executor and close handlers")
        void shouldShutdownExecutor() {
            when(config.enabled()).thenReturn(true);
            when(securityConfig.enabled()).thenReturn(true);

            var dispatcher = new SecurityEventDispatcher(config, registry);
            dispatcher.init();
            assertDoesNotThrow(dispatcher::shutdown);
        }
    }
}
