package aussie.adapter.out.storage.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.session.Session;

@DisplayName("InMemorySessionRepository")
class InMemorySessionRepositoryTest {

    private InMemorySessionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemorySessionRepository();
    }

    private Session createTestSession(String id, String userId) {
        return new Session(
                id,
                userId,
                "https://auth.example.com",
                Map.of("email", "user@example.com"),
                Set.of("admin:read"),
                Instant.now(),
                Instant.now().plus(Duration.ofHours(8)),
                Instant.now(),
                "Mozilla/5.0",
                "192.168.1.1");
    }

    @Nested
    @DisplayName("saveIfAbsent")
    class SaveIfAbsentTests {

        @Test
        @DisplayName("should return true when session is new")
        void shouldReturnTrueWhenSessionIsNew() {
            var session = createTestSession("session-1", "user-1");

            var result = repository.saveIfAbsent(session).await().indefinitely();

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when session ID already exists")
        void shouldReturnFalseWhenSessionIdAlreadyExists() {
            var session1 = createTestSession("session-1", "user-1");
            var session2 = createTestSession("session-1", "user-2");

            repository.saveIfAbsent(session1).await().indefinitely();
            var result = repository.saveIfAbsent(session2).await().indefinitely();

            assertFalse(result);
        }

        @Test
        @DisplayName("should preserve original session on collision")
        void shouldPreserveOriginalSessionOnCollision() {
            var session1 = createTestSession("session-1", "user-1");
            var session2 = createTestSession("session-1", "user-2");

            repository.saveIfAbsent(session1).await().indefinitely();
            repository.saveIfAbsent(session2).await().indefinitely();

            var found = repository.findById("session-1").await().indefinitely();
            assertTrue(found.isPresent());
            assertEquals("user-1", found.get().userId());
        }
    }

    @Nested
    @DisplayName("save")
    class SaveTests {

        @Test
        @DisplayName("should save new session")
        void shouldSaveNewSession() {
            var session = createTestSession("session-1", "user-1");

            var result = repository.save(session).await().indefinitely();

            assertEquals(session.id(), result.id());
        }

        @Test
        @DisplayName("should overwrite existing session")
        void shouldOverwriteExistingSession() {
            var session1 = createTestSession("session-1", "user-1");
            var session2 = createTestSession("session-1", "user-2");

            repository.save(session1).await().indefinitely();
            repository.save(session2).await().indefinitely();

            var found = repository.findById("session-1").await().indefinitely();
            assertTrue(found.isPresent());
            assertEquals("user-2", found.get().userId());
        }
    }

    @Nested
    @DisplayName("findById")
    class FindByIdTests {

        @Test
        @DisplayName("should return session when found")
        void shouldReturnSessionWhenFound() {
            var session = createTestSession("session-1", "user-1");
            repository.save(session).await().indefinitely();

            var result = repository.findById("session-1").await().indefinitely();

            assertTrue(result.isPresent());
            assertEquals("session-1", result.get().id());
        }

        @Test
        @DisplayName("should return empty when not found")
        void shouldReturnEmptyWhenNotFound() {
            var result = repository.findById("non-existent").await().indefinitely();

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return session even if expired (expiration is service layer concern)")
        void shouldReturnSessionEvenIfExpired() {
            var expiredSession = new Session(
                    "expired-session",
                    "user-1",
                    "issuer",
                    Map.of(),
                    Set.of(),
                    Instant.now().minus(Duration.ofHours(10)),
                    Instant.now().minus(Duration.ofHours(1)), // Expired
                    Instant.now().minus(Duration.ofHours(10)),
                    null,
                    null);
            repository.save(expiredSession).await().indefinitely();

            var result = repository.findById("expired-session").await().indefinitely();

            // Repository should return the session regardless of expiration
            // Expiration checking is the service layer's responsibility
            assertTrue(result.isPresent());
        }
    }

    @Nested
    @DisplayName("update")
    class UpdateTests {

        @Test
        @DisplayName("should update session")
        void shouldUpdateSession() {
            var session = createTestSession("session-1", "user-1");
            repository.save(session).await().indefinitely();

            var updated = session.withLastAccessedAt(Instant.now());
            repository.update(updated).await().indefinitely();

            var found = repository.findById("session-1").await().indefinitely();
            assertTrue(found.isPresent());
            assertEquals(updated.lastAccessedAt(), found.get().lastAccessedAt());
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("should delete session")
        void shouldDeleteSession() {
            var session = createTestSession("session-1", "user-1");
            repository.save(session).await().indefinitely();

            repository.delete("session-1").await().indefinitely();

            var found = repository.findById("session-1").await().indefinitely();
            assertTrue(found.isEmpty());
        }

        @Test
        @DisplayName("should handle deleting non-existent session")
        void shouldHandleDeletingNonExistentSession() {
            // Should not throw
            repository.delete("non-existent").await().indefinitely();
        }
    }

    @Nested
    @DisplayName("deleteByUserId")
    class DeleteByUserIdTests {

        @Test
        @DisplayName("should delete all sessions for user")
        void shouldDeleteAllSessionsForUser() {
            var session1 = createTestSession("session-1", "user-1");
            var session2 = createTestSession("session-2", "user-1");
            var session3 = createTestSession("session-3", "user-2");

            repository.save(session1).await().indefinitely();
            repository.save(session2).await().indefinitely();
            repository.save(session3).await().indefinitely();

            repository.deleteByUserId("user-1").await().indefinitely();

            assertTrue(repository.findById("session-1").await().indefinitely().isEmpty());
            assertTrue(repository.findById("session-2").await().indefinitely().isEmpty());
            assertTrue(repository.findById("session-3").await().indefinitely().isPresent());
        }
    }

    @Nested
    @DisplayName("exists")
    class ExistsTests {

        @Test
        @DisplayName("should return true when session exists")
        void shouldReturnTrueWhenSessionExists() {
            var session = createTestSession("session-1", "user-1");
            repository.save(session).await().indefinitely();

            var result = repository.exists("session-1").await().indefinitely();

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false when session does not exist")
        void shouldReturnFalseWhenSessionDoesNotExist() {
            var result = repository.exists("non-existent").await().indefinitely();

            assertFalse(result);
        }
    }
}
