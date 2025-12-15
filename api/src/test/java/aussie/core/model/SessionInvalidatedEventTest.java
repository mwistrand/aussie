package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.session.*;

@DisplayName("SessionInvalidatedEvent")
class SessionInvalidatedEventTest {

    @Nested
    @DisplayName("forSession")
    class ForSessionTests {

        @Test
        @DisplayName("should create event with session ID")
        void shouldCreateEventWithSessionId() {
            var event = SessionInvalidatedEvent.forSession("session-123");

            assertTrue(event.sessionId().isPresent());
            assertTrue(event.userId().isEmpty());
            assertTrue(event.appliesTo("session-123", "any-user"));
        }

        @Test
        @DisplayName("should not apply to different session")
        void shouldNotApplyToDifferentSession() {
            var event = SessionInvalidatedEvent.forSession("session-123");

            assertFalse(event.appliesTo("session-456", "any-user"));
        }
    }

    @Nested
    @DisplayName("forUser")
    class ForUserTests {

        @Test
        @DisplayName("should create event with user ID")
        void shouldCreateEventWithUserId() {
            var event = SessionInvalidatedEvent.forUser("user-123");

            assertTrue(event.userId().isPresent());
            assertTrue(event.sessionId().isEmpty());
            assertTrue(event.appliesTo("any-session", "user-123"));
        }

        @Test
        @DisplayName("should not apply to different user")
        void shouldNotApplyToDifferentUser() {
            var event = SessionInvalidatedEvent.forUser("user-123");

            assertFalse(event.appliesTo("any-session", "user-456"));
        }

        @Test
        @DisplayName("should apply to any session of the user")
        void shouldApplyToAnySessionOfUser() {
            var event = SessionInvalidatedEvent.forUser("user-123");

            assertTrue(event.appliesTo("session-1", "user-123"));
            assertTrue(event.appliesTo("session-2", "user-123"));
            assertTrue(event.appliesTo("session-3", "user-123"));
        }
    }

    @Nested
    @DisplayName("appliesTo")
    class AppliesToTests {

        @Test
        @DisplayName("should match by session ID when both are provided")
        void shouldMatchBySessionId() {
            var event = new SessionInvalidatedEvent(Optional.of("session-123"), Optional.of("user-123"));

            // Matches because session ID matches
            assertTrue(event.appliesTo("session-123", "user-456"));
        }

        @Test
        @DisplayName("should match by user ID when session doesn't match")
        void shouldMatchByUserId() {
            var event = new SessionInvalidatedEvent(Optional.of("session-123"), Optional.of("user-123"));

            // Session doesn't match but user does
            assertTrue(event.appliesTo("session-456", "user-123"));
        }

        @Test
        @DisplayName("should not match when neither session nor user match")
        void shouldNotMatchWhenNeitherMatch() {
            var event = new SessionInvalidatedEvent(Optional.of("session-123"), Optional.of("user-123"));

            assertFalse(event.appliesTo("session-456", "user-456"));
        }

        @Test
        @DisplayName("should handle null user ID in appliesTo")
        void shouldHandleNullUserIdInAppliesTo() {
            var event = SessionInvalidatedEvent.forUser("user-123");

            assertFalse(event.appliesTo("any-session", null));
        }
    }
}
