package aussie.core.model.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Session model")
class SessionModelTest {

    private Session createSession(Instant expiresAt, Instant lastAccessedAt) {
        return new Session(
                "session-1",
                "user-1",
                "https://issuer.example.com",
                Map.of(),
                Set.of("read"),
                Instant.now(),
                expiresAt,
                lastAccessedAt,
                "Mozilla/5.0",
                "10.0.0.1");
    }

    @Nested
    @DisplayName("Session")
    class SessionTests {

        @Test
        @DisplayName("withId should return session with new ID")
        void withIdShouldReturnNewId() {
            var session = createSession(Instant.now().plusSeconds(3600), Instant.now());

            var updated = session.withId("new-id");

            assertEquals("new-id", updated.id());
            assertEquals(session.userId(), updated.userId());
        }

        @Test
        @DisplayName("isExpired should return false when expiresAt is null")
        void isExpiredShouldReturnFalseWhenNull() {
            var session = createSession(null, Instant.now());

            assertFalse(session.isExpired());
        }

        @Test
        @DisplayName("isExpired should return false when not yet expired")
        void isExpiredShouldReturnFalseWhenNotExpired() {
            var session = createSession(Instant.now().plusSeconds(3600), Instant.now());

            assertFalse(session.isExpired());
        }

        @Test
        @DisplayName("isExpired should return true when expired")
        void isExpiredShouldReturnTrueWhenExpired() {
            var session = createSession(Instant.now().minusSeconds(1), Instant.now());

            assertTrue(session.isExpired());
        }

        @Test
        @DisplayName("isIdle should return false when lastAccessedAt is null")
        void isIdleShouldReturnFalseWhenLastAccessedNull() {
            var session = createSession(Instant.now().plusSeconds(3600), null);

            assertFalse(session.isIdle(Duration.ofMinutes(30)));
        }

        @Test
        @DisplayName("isIdle should return false when idleTimeout is null")
        void isIdleShouldReturnFalseWhenIdleTimeoutNull() {
            var session = createSession(Instant.now().plusSeconds(3600), Instant.now());

            assertFalse(session.isIdle(null));
        }

        @Test
        @DisplayName("isIdle should return false when within idle timeout")
        void isIdleShouldReturnFalseWhenWithinTimeout() {
            var session = createSession(Instant.now().plusSeconds(3600), Instant.now());

            assertFalse(session.isIdle(Duration.ofMinutes(30)));
        }

        @Test
        @DisplayName("isIdle should return true when idle timeout exceeded")
        void isIdleShouldReturnTrueWhenExceeded() {
            var session =
                    createSession(Instant.now().plusSeconds(3600), Instant.now().minusSeconds(3600));

            assertTrue(session.isIdle(Duration.ofMinutes(30)));
        }

        @Test
        @DisplayName("isValid should return true for non-expired non-idle session")
        void isValidShouldReturnTrueForValidSession() {
            var session = createSession(Instant.now().plusSeconds(3600), Instant.now());

            assertTrue(session.isValid(Duration.ofMinutes(30)));
        }

        @Test
        @DisplayName("isValid should return true when idleTimeout is null")
        void isValidShouldReturnTrueWhenIdleTimeoutNull() {
            var session = createSession(Instant.now().plusSeconds(3600), Instant.now());

            assertTrue(session.isValid(null));
        }

        @Test
        @DisplayName("isValid should return false when expired")
        void isValidShouldReturnFalseWhenExpired() {
            var session = createSession(Instant.now().minusSeconds(1), Instant.now());

            assertFalse(session.isValid(Duration.ofMinutes(30)));
        }

        @Test
        @DisplayName("isValid should return false when idle")
        void isValidShouldReturnFalseWhenIdle() {
            var session =
                    createSession(Instant.now().plusSeconds(3600), Instant.now().minusSeconds(3600));

            assertFalse(session.isValid(Duration.ofMinutes(30)));
        }
    }

    @Nested
    @DisplayName("SessionToken")
    class SessionTokenTests {

        @Test
        @DisplayName("isExpired should return false when expiresAt is null")
        void isExpiredShouldReturnFalseWhenNull() {
            var token = new SessionToken("jwt-token", null, "session-1", Set.of("sub", "email"));

            assertFalse(token.isExpired());
        }

        @Test
        @DisplayName("isExpired should return false when not expired")
        void isExpiredShouldReturnFalseWhenNotExpired() {
            var token = new SessionToken("jwt-token", Instant.now().plusSeconds(3600), "session-1", Set.of("sub"));

            assertFalse(token.isExpired());
        }

        @Test
        @DisplayName("isExpired should return true when expired")
        void isExpiredShouldReturnTrueWhenExpired() {
            var token = new SessionToken("jwt-token", Instant.now().minusSeconds(1), "session-1", Set.of("sub"));

            assertTrue(token.isExpired());
        }
    }
}
