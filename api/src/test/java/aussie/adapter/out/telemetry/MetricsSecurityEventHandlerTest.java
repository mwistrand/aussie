package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.spi.SecurityEvent;

@DisplayName("MetricsSecurityEventHandler")
class MetricsSecurityEventHandlerTest {

    private SimpleMeterRegistry registry;
    private MetricsSecurityEventHandler handler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        handler = new MetricsSecurityEventHandler(registry);
    }

    @Nested
    @DisplayName("name()")
    class NameTests {

        @Test
        @DisplayName("should return 'metrics'")
        void shouldReturnMetrics() {
            assertEquals("metrics", handler.name());
        }
    }

    @Nested
    @DisplayName("priority()")
    class PriorityTests {

        @Test
        @DisplayName("should return 10")
        void shouldReturnTen() {
            assertEquals(10, handler.priority());
        }
    }

    @Nested
    @DisplayName("isAvailable()")
    class IsAvailableTests {

        @Test
        @DisplayName("should return true when registry set")
        void shouldReturnTrueWhenRegistrySet() {
            assertTrue(handler.isAvailable());
        }

        @Test
        @DisplayName("should return false with default constructor")
        void shouldReturnFalseWithDefaultConstructor() {
            var defaultHandler = new MetricsSecurityEventHandler();
            assertFalse(defaultHandler.isAvailable());
        }

        @Test
        @DisplayName("should return true after setMeterRegistry")
        void shouldReturnTrueAfterSet() {
            var defaultHandler = new MetricsSecurityEventHandler();
            defaultHandler.setMeterRegistry(registry);
            assertTrue(defaultHandler.isAvailable());
        }
    }

    @Nested
    @DisplayName("handle()")
    class HandleTests {

        @Test
        @DisplayName("should skip when registry is null")
        void shouldSkipWhenRegistryNull() {
            var noRegistryHandler = new MetricsSecurityEventHandler();
            var event = new SecurityEvent.AuthenticationFailure(Instant.now(), "client-1", "invalid_key", "api_key", 1);
            assertDoesNotThrow(() -> noRegistryHandler.handle(event));
        }

        @Test
        @DisplayName("should record generic event counter for AuthenticationFailure")
        void shouldRecordGenericEventCounter() {
            var event = new SecurityEvent.AuthenticationFailure(Instant.now(), "client-1", "invalid_key", "api_key", 1);
            handler.handle(event);

            var counter = registry.find("aussie.security.events.total")
                    .tag("event_type", "AuthenticationFailure")
                    .tag("severity", "info")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
        }

        @Test
        @DisplayName("should record auth failure metric")
        void shouldRecordAuthFailure() {
            var event = new SecurityEvent.AuthenticationFailure(Instant.now(), "client-1", "invalid_key", "api_key", 1);
            handler.handle(event);

            var counter = registry.find("aussie.security.auth.failures")
                    .tag("reason", "invalid_key")
                    .tag("method", "api_key")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
            assertNull(counter.getId().getTag("client_ip_hash"));
        }

        @Test
        @DisplayName("should record auth lockout metric")
        void shouldRecordAuthLockout() {
            var event = new SecurityEvent.AuthenticationLockout(Instant.now(), "client-1", "ip:1.2.3.4", 5, 300, 1);
            handler.handle(event);

            var counter = registry.find("aussie.security.auth.lockouts")
                    .tag("key_type", "ip")
                    .counter();
            assertNotNull(counter);
            assertEquals(1.0, counter.count());
            assertNull(counter.getId().getTag("client_ip_hash"));
        }

        @Test
        @DisplayName("should extract key type from locked key with colon prefix")
        void shouldExtractKeyType() {
            var event = new SecurityEvent.AuthenticationLockout(Instant.now(), "client-1", "user:john", 5, 300, 1);
            handler.handle(event);

            var counter = registry.find("aussie.security.auth.lockouts")
                    .tag("key_type", "user")
                    .counter();
            assertNotNull(counter);
        }

        @Test
        @DisplayName("should use 'unknown' for null locked key")
        void shouldUseUnknownForNullLockedKey() {
            var event = new SecurityEvent.AuthenticationLockout(Instant.now(), "client-1", null, 5, 300, 1);
            handler.handle(event);

            var counter = registry.find("aussie.security.auth.lockouts")
                    .tag("key_type", "unknown")
                    .counter();
            assertNotNull(counter);
        }

        @Test
        @DisplayName("should use 'unknown' for locked key without colon")
        void shouldUseUnknownForKeyWithoutColon() {
            var event = new SecurityEvent.AuthenticationLockout(Instant.now(), "client-1", "somekey", 5, 300, 1);
            handler.handle(event);

            var counter = registry.find("aussie.security.auth.lockouts")
                    .tag("key_type", "unknown")
                    .counter();
            assertNotNull(counter);
        }

        @Test
        @DisplayName("should record access denied metric")
        void shouldRecordAccessDenied() {
            var event = new SecurityEvent.AccessDenied(Instant.now(), "client-1", "svc-1", "/admin", "ip_blocked");
            handler.handle(event);

            var counter = registry.find("aussie.security.access.denied")
                    .tag("service_id", "svc-1")
                    .tag("reason", "ip_blocked")
                    .counter();
            assertNotNull(counter);
        }

        @Test
        @DisplayName("should use 'unknown' for null service ID in access denied")
        void shouldUseUnknownForNullServiceId() {
            var event = new SecurityEvent.AccessDenied(Instant.now(), "client-1", null, "/admin", "forbidden");
            handler.handle(event);

            var counter = registry.find("aussie.security.access.denied")
                    .tag("service_id", "unknown")
                    .counter();
            assertNotNull(counter);
        }

        @Test
        @DisplayName("should record rate limit exceeded metric")
        void shouldRecordRateLimitExceeded() {
            var event = new SecurityEvent.RateLimitExceeded(Instant.now(), "client-1", "svc-1", 150, 100, 60);
            handler.handle(event);

            var counter = registry.find("aussie.security.rate_limit.exceeded")
                    .tag("service_id", "svc-1")
                    .counter();
            assertNotNull(counter);
            assertNull(counter.getId().getTag("client_ip_hash"));
        }

        @Test
        @DisplayName("should record suspicious pattern metric")
        void shouldRecordSuspiciousPattern() {
            var event = new SecurityEvent.SuspiciousPattern(Instant.now(), "client-1", "brute_force", "details", 0.8);
            handler.handle(event);

            var counter = registry.find("aussie.security.suspicious.patterns")
                    .tag("pattern_type", "brute_force")
                    .counter();
            assertNotNull(counter);
            assertNull(counter.getId().getTag("client_ip_hash"));
        }

        @Test
        @DisplayName("should record DoS attack metric")
        void shouldRecordDosAttack() {
            var event = new SecurityEvent.DosAttackDetected(Instant.now(), "client-1", "request_flood", Map.of());
            handler.handle(event);

            var counter = registry.find("aussie.security.dos.detected")
                    .tag("attack_type", "request_flood")
                    .counter();
            assertNotNull(counter);
            assertNull(counter.getId().getTag("client_ip_hash"));
        }

        @Test
        @DisplayName("should record session invalidated metric")
        void shouldRecordSessionInvalidated() {
            var event =
                    new SecurityEvent.SessionInvalidated(Instant.now(), "client-1", "session-1", "user-1", "logout");
            handler.handle(event);

            var counter = registry.find("aussie.security.session.invalidated")
                    .tag("reason", "logout")
                    .counter();
            assertNotNull(counter);
        }
    }
}
