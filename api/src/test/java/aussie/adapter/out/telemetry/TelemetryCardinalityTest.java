package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import aussie.spi.SecurityEvent;

class TelemetryCardinalityTest {

    @Test
    void unrecognizedSecurityLabelsShareTheOtherSeries() {
        final var registry = new SimpleMeterRegistry();
        final var handler = new MetricsSecurityEventHandler(registry);

        for (int i = 0; i < 1_000; i++) {
            handler.handle(new SecurityEvent.AuthenticationFailure(
                    Instant.now(), "client-" + i, "attacker-controlled-" + i, "method-" + i, 1));
            handler.handle(new SecurityEvent.AuthenticationLockout(
                    Instant.now(), "client-" + i, "key-type-" + i + ":value", 5, 60, 1));
            handler.handle(
                    new SecurityEvent.AccessDenied(Instant.now(), "client-" + i, "service", "/route", "reason-" + i));
            handler.handle(
                    new SecurityEvent.SuspiciousPattern(Instant.now(), "client-" + i, "pattern-" + i, "details", 0.8));
            handler.handle(new SecurityEvent.DosAttackDetected(Instant.now(), "client-" + i, "attack-" + i, Map.of()));
            handler.handle(new SecurityEvent.SessionInvalidated(
                    Instant.now(), "client-" + i, "session", "user", "reason-" + i));
        }

        assertEquals(1, registry.find("aussie.security.auth.failures").meters().size());
        assertEquals(1, registry.find("aussie.security.auth.lockouts").meters().size());
        assertEquals(1, registry.find("aussie.security.access.denied").meters().size());
        assertEquals(
                1, registry.find("aussie.security.suspicious.patterns").meters().size());
        assertEquals(1, registry.find("aussie.security.dos.detected").meters().size());
        assertEquals(
                1, registry.find("aussie.security.session.invalidated").meters().size());
        assertEquals(
                1_000,
                registry.find("aussie.security.auth.failures")
                        .tag("reason", "other")
                        .tag("method", "other")
                        .counter()
                        .count());
    }

    @Test
    void productionSecurityLabelsRemainDistinct() {
        final var registry = new SimpleMeterRegistry();
        final var handler = new MetricsSecurityEventHandler(registry);

        handler.handle(
                new SecurityEvent.AuthenticationFailure(Instant.now(), "client", "invalid_session", "bearer", 1));
        handler.handle(
                new SecurityEvent.SuspiciousPattern(Instant.now(), "client", "brute_force_attempt", "details", 0.8));

        assertEquals(
                1,
                registry.find("aussie.security.auth.failures")
                        .tag("reason", "invalid_session")
                        .tag("method", "bearer")
                        .counter()
                        .count());
        assertEquals(
                1,
                registry.find("aussie.security.suspicious.patterns")
                        .tag("pattern_type", "brute_force_attempt")
                        .counter()
                        .count());
    }
}
