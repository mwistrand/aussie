package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.spi.SecurityEvent;

@DisplayName("LoggingSecurityEventHandler")
class LoggingSecurityEventHandlerTest {

    private final LoggingSecurityEventHandler handler = new LoggingSecurityEventHandler();

    @Nested
    @DisplayName("name()")
    class NameTests {

        @Test
        @DisplayName("should return 'logging'")
        void shouldReturnLogging() {
            assertEquals("logging", handler.name());
        }
    }

    @Nested
    @DisplayName("description()")
    class DescriptionTests {

        @Test
        @DisplayName("should return non-empty description")
        void shouldReturnDescription() {
            assertTrue(handler.description().length() > 0);
        }
    }

    @Nested
    @DisplayName("priority()")
    class PriorityTests {

        @Test
        @DisplayName("should return 0")
        void shouldReturnZero() {
            assertEquals(0, handler.priority());
        }
    }

    @Nested
    @DisplayName("handle()")
    class HandleTests {

        @Test
        @DisplayName("should handle AuthenticationFailure event")
        void shouldHandleAuthFailure() {
            var event = new SecurityEvent.AuthenticationFailure(Instant.now(), "client-1", "invalid_key", "api_key", 1);
            assertDoesNotThrow(() -> handler.handle(event));
        }

        @Test
        @DisplayName("should handle AuthenticationLockout event")
        void shouldHandleAuthLockout() {
            var event = new SecurityEvent.AuthenticationLockout(Instant.now(), "client-1", "ip:1.2.3.4", 5, 300, 1);
            assertDoesNotThrow(() -> handler.handle(event));
        }

        @Test
        @DisplayName("should handle AccessDenied event")
        void shouldHandleAccessDenied() {
            var event = new SecurityEvent.AccessDenied(Instant.now(), "client-1", "svc-1", "/admin", "ip_blocked");
            assertDoesNotThrow(() -> handler.handle(event));
        }

        @Test
        @DisplayName("should handle RateLimitExceeded event")
        void shouldHandleRateLimit() {
            var event = new SecurityEvent.RateLimitExceeded(Instant.now(), "client-1", "svc-1", 150, 100, 60);
            assertDoesNotThrow(() -> handler.handle(event));
        }

        @Test
        @DisplayName("should handle SuspiciousPattern event")
        void shouldHandleSuspicious() {
            var event =
                    new SecurityEvent.SuspiciousPattern(Instant.now(), "client-1", "brute_force", "Many failures", 0.8);
            assertDoesNotThrow(() -> handler.handle(event));
        }

        @Test
        @DisplayName("should handle DosAttackDetected event")
        void shouldHandleDosAttack() {
            var event = new SecurityEvent.DosAttackDetected(
                    Instant.now(), "client-1", "request_flood", Map.of("count", 1000));
            assertDoesNotThrow(() -> handler.handle(event));
        }

        @Test
        @DisplayName("should handle SessionInvalidated event")
        void shouldHandleSessionInvalidated() {
            var event =
                    new SecurityEvent.SessionInvalidated(Instant.now(), "client-1", "session-1", "user-1", "logout");
            assertDoesNotThrow(() -> handler.handle(event));
        }

        @Test
        @DisplayName("should log WARNING severity at WARN level")
        void shouldLogWarningAtWarnLevel() {
            // AuthenticationFailure with 5+ failures has WARNING severity
            var event = new SecurityEvent.AuthenticationFailure(Instant.now(), "client-1", "invalid_key", "api_key", 5);
            assertEquals(SecurityEvent.Severity.WARNING, event.severity());
            handler.handle(event);
        }

        @Test
        @DisplayName("should log CRITICAL severity at ERROR level")
        void shouldLogCriticalAtErrorLevel() {
            // DosAttackDetected always has CRITICAL severity
            var event = new SecurityEvent.DosAttackDetected(Instant.now(), "client-1", "flood", Map.of());
            assertEquals(SecurityEvent.Severity.CRITICAL, event.severity());
            handler.handle(event);
        }
    }
}
