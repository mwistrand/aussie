package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.spi.SecurityEvent;
import aussie.spi.SecurityEventHandler;

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
        when(securityConfig.eventQueueCapacity()).thenReturn(1000);
        when(securityConfig.shutdownDrainTimeout()).thenReturn(Duration.ofSeconds(1));
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
        @DisplayName("should reject events when the bounded queue is full")
        void shouldRejectWhenQueueIsFull() throws Exception {
            when(config.enabled()).thenReturn(true);
            when(securityConfig.enabled()).thenReturn(true);
            when(securityConfig.eventQueueCapacity()).thenReturn(1);
            final var started = new CountDownLatch(1);
            final var release = new CountDownLatch(1);
            final var handled = new AtomicInteger();
            final SecurityEventHandler handler = new SecurityEventHandler() {
                @Override
                public String name() {
                    return "blocking";
                }

                @Override
                public void handle(SecurityEvent event) {
                    handled.incrementAndGet();
                    started.countDown();
                    try {
                        release.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            };
            final var dispatcher = new SecurityEventDispatcher(config, registry, List.of(handler));
            dispatcher.init();
            final var event = new SecurityEvent.DosAttackDetected(Instant.now(), "client-1", "flood", Map.of());

            dispatcher.dispatch(event);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            dispatcher.dispatch(event);
            dispatcher.dispatch(event);
            assertEquals(
                    1.0,
                    registry.get("aussie.security.events.dispatch.rejected")
                            .counter()
                            .count());

            release.countDown();
            dispatcher.shutdown();
            assertEquals(2, handled.get());
        }

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

        @Test
        @DisplayName("should reject a non-positive queue capacity")
        void shouldRejectNonPositiveQueueCapacity() {
            when(config.enabled()).thenReturn(true);
            when(securityConfig.enabled()).thenReturn(true);
            when(securityConfig.eventQueueCapacity()).thenReturn(0);

            final var dispatcher = new SecurityEventDispatcher(config, registry);

            final var error = assertThrows(IllegalStateException.class, dispatcher::init);
            assertEquals("aussie.telemetry.security.event-queue-capacity must be positive", error.getMessage());
        }

        @Test
        @DisplayName("should reject a non-positive shutdown drain timeout")
        void shouldRejectNonPositiveShutdownDrainTimeout() {
            when(config.enabled()).thenReturn(true);
            when(securityConfig.enabled()).thenReturn(true);
            when(securityConfig.shutdownDrainTimeout()).thenReturn(Duration.ZERO);

            final var dispatcher = new SecurityEventDispatcher(config, registry);

            final var error = assertThrows(IllegalStateException.class, dispatcher::init);
            assertEquals("aussie.telemetry.security.shutdown-drain-timeout must be positive", error.getMessage());
        }
    }

    @Nested
    @DisplayName("shutdown()")
    class ShutdownTests {

        @Test
        @DisplayName("should force-stop work that exceeds the drain timeout")
        void shouldForceStopWorkAfterDrainTimeout() throws Exception {
            when(config.enabled()).thenReturn(true);
            when(securityConfig.enabled()).thenReturn(true);
            when(securityConfig.shutdownDrainTimeout()).thenReturn(Duration.ofMillis(10));
            final var started = new CountDownLatch(1);
            final var closed = new AtomicInteger();
            final SecurityEventHandler handler = new SecurityEventHandler() {
                @Override
                public String name() {
                    return "blocking";
                }

                @Override
                public void handle(SecurityEvent event) {
                    started.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                @Override
                public void close() {
                    closed.incrementAndGet();
                }
            };
            final var dispatcher = new SecurityEventDispatcher(config, registry, List.of(handler));
            dispatcher.init();
            dispatcher.dispatch(new SecurityEvent.DosAttackDetected(Instant.now(), "client-1", "flood", Map.of()));
            assertTrue(started.await(1, TimeUnit.SECONDS));

            dispatcher.shutdown();

            assertEquals(1, closed.get());
            assertEquals(
                    1.0,
                    registry.get("aussie.security.events.dispatch.shutdown_timeouts")
                            .counter()
                            .count());
        }

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
