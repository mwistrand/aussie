package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import aussie.spi.SecurityEvent;

class LoggingSecurityRedactionTest {

    @Test
    void securityLogFormattingOmitsSecretsAndEvidence() {
        final var handler = new LoggingSecurityEventHandler();
        final var lockout =
                new SecurityEvent.AuthenticationLockout(Instant.now(), "client-hash", "ip:192.0.2.10", 5, 60, 1);
        final var unknownLockout =
                new SecurityEvent.AuthenticationLockout(Instant.now(), "client-hash", "secret-prefix:value", 5, 60, 1);
        final var session = new SecurityEvent.SessionInvalidated(
                Instant.now(), "client-hash", "session-secret", "user-secret", "logout");

        final var lockoutLog = handler.formatEvent(lockout);
        final var unknownLockoutLog = handler.formatEvent(unknownLockout);
        final var sessionLog = handler.formatEvent(session);

        assertTrue(lockoutLog.contains("key_type=ip"));
        assertFalse(lockoutLog.contains("192.0.2.10"));
        assertTrue(unknownLockoutLog.contains("key_type=unknown"));
        assertFalse(unknownLockoutLog.contains("secret-prefix"));
        assertFalse(sessionLog.contains("session-secret"));
        assertFalse(sessionLog.contains("user-secret"));
        assertFalse(handler.formatEvent(new SecurityEvent.SuspiciousPattern(
                        Instant.now(), "client-hash", "brute_force_attempt", "token=secret", 0.8))
                .contains("token=secret"));
        assertTrue(handler.formatEvent(new SecurityEvent.DosAttackDetected(
                        Instant.now(), "client-hash", "request_flood", Map.of("token", "secret")))
                .contains("evidence_present=true"));
    }
}
