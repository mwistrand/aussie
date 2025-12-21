package aussie.adapter.out.storage.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryTokenRevocationRepository")
class InMemoryTokenRevocationRepositoryTest {

    private InMemoryTokenRevocationRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTokenRevocationRepository();
    }

    @AfterEach
    void tearDown() {
        repository.shutdown();
    }

    @Nested
    @DisplayName("revoke()")
    class RevokeTests {

        @Test
        @DisplayName("should store revocation")
        void shouldStoreRevocation() {
            final var jti = "test-jti";
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            repository.revoke(jti, expiresAt).await().atMost(Duration.ofSeconds(1));

            assertEquals(1, repository.getRevokedJtiCount());
        }

        @Test
        @DisplayName("should replace existing revocation")
        void shouldReplaceExistingRevocation() {
            final var jti = "test-jti";
            final var expiresAt1 = Instant.now().plus(Duration.ofHours(1));
            final var expiresAt2 = Instant.now().plus(Duration.ofHours(2));

            repository.revoke(jti, expiresAt1).await().atMost(Duration.ofSeconds(1));
            repository.revoke(jti, expiresAt2).await().atMost(Duration.ofSeconds(1));

            assertEquals(1, repository.getRevokedJtiCount());
        }
    }

    @Nested
    @DisplayName("isRevoked()")
    class IsRevokedTests {

        @Test
        @DisplayName("should return true for revoked JTI")
        void shouldReturnTrueForRevokedJti() {
            final var jti = "revoked-jti";
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            repository.revoke(jti, expiresAt).await().atMost(Duration.ofSeconds(1));

            final var result = repository.isRevoked(jti).await().atMost(Duration.ofSeconds(1));

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false for non-revoked JTI")
        void shouldReturnFalseForNonRevokedJti() {
            final var result = repository.isRevoked("unknown-jti").await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false for expired revocation")
        void shouldReturnFalseForExpiredRevocation() {
            final var jti = "expired-jti";
            final var expiresAt = Instant.now().minus(Duration.ofSeconds(1)); // Already expired

            repository.revoke(jti, expiresAt).await().atMost(Duration.ofSeconds(1));

            final var result = repository.isRevoked(jti).await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("revokeAllForUser()")
    class RevokeAllForUserTests {

        @Test
        @DisplayName("should store user revocation")
        void shouldStoreUserRevocation() {
            final var userId = "user-123";
            final var issuedBefore = Instant.now();
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            repository.revokeAllForUser(userId, issuedBefore, expiresAt).await().atMost(Duration.ofSeconds(1));

            assertEquals(1, repository.getRevokedUserCount());
        }
    }

    @Nested
    @DisplayName("isUserRevoked()")
    class IsUserRevokedTests {

        @Test
        @DisplayName("should return true for token issued before cutoff")
        void shouldReturnTrueForTokenIssuedBeforeCutoff() {
            final var userId = "user-123";
            final var issuedBefore = Instant.now();
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            repository.revokeAllForUser(userId, issuedBefore, expiresAt).await().atMost(Duration.ofSeconds(1));

            // Token issued before the cutoff
            final var issuedAt = issuedBefore.minus(Duration.ofMinutes(5));
            final var result =
                    repository.isUserRevoked(userId, issuedAt).await().atMost(Duration.ofSeconds(1));

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false for token issued after cutoff")
        void shouldReturnFalseForTokenIssuedAfterCutoff() {
            final var userId = "user-123";
            final var issuedBefore = Instant.now().minus(Duration.ofHours(1));
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            repository.revokeAllForUser(userId, issuedBefore, expiresAt).await().atMost(Duration.ofSeconds(1));

            // Token issued after the cutoff
            final var issuedAt = Instant.now();
            final var result =
                    repository.isUserRevoked(userId, issuedAt).await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false for unknown user")
        void shouldReturnFalseForUnknownUser() {
            final var result = repository
                    .isUserRevoked("unknown-user", Instant.now())
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false for expired user revocation")
        void shouldReturnFalseForExpiredUserRevocation() {
            final var userId = "user-123";
            final var issuedBefore = Instant.now();
            final var expiresAt = Instant.now().minus(Duration.ofSeconds(1)); // Already expired

            repository.revokeAllForUser(userId, issuedBefore, expiresAt).await().atMost(Duration.ofSeconds(1));

            final var issuedAt = issuedBefore.minus(Duration.ofMinutes(5));
            final var result =
                    repository.isUserRevoked(userId, issuedAt).await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("streamAllRevokedJtis()")
    class StreamAllRevokedJtisTests {

        @Test
        @DisplayName("should return all revoked JTIs")
        void shouldReturnAllRevokedJtis() {
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            repository.revoke("jti-1", expiresAt).await().atMost(Duration.ofSeconds(1));
            repository.revoke("jti-2", expiresAt).await().atMost(Duration.ofSeconds(1));
            repository.revoke("jti-3", expiresAt).await().atMost(Duration.ofSeconds(1));

            final var jtis =
                    repository.streamAllRevokedJtis().collect().asList().await().atMost(Duration.ofSeconds(1));

            assertEquals(3, jtis.size());
            assertTrue(jtis.contains("jti-1"));
            assertTrue(jtis.contains("jti-2"));
            assertTrue(jtis.contains("jti-3"));
        }

        @Test
        @DisplayName("should return empty when no revocations")
        void shouldReturnEmptyWhenNoRevocations() {
            final var jtis =
                    repository.streamAllRevokedJtis().collect().asList().await().atMost(Duration.ofSeconds(1));

            assertTrue(jtis.isEmpty());
        }
    }

    @Nested
    @DisplayName("streamAllRevokedUsers()")
    class StreamAllRevokedUsersTests {

        @Test
        @DisplayName("should return all revoked users")
        void shouldReturnAllRevokedUsers() {
            final var issuedBefore = Instant.now();
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            repository
                    .revokeAllForUser("user-1", issuedBefore, expiresAt)
                    .await()
                    .atMost(Duration.ofSeconds(1));
            repository
                    .revokeAllForUser("user-2", issuedBefore, expiresAt)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            final var users = repository
                    .streamAllRevokedUsers()
                    .collect()
                    .asList()
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertEquals(2, users.size());
            assertTrue(users.contains("user-1"));
            assertTrue(users.contains("user-2"));
        }
    }

    @Nested
    @DisplayName("clear()")
    class ClearTests {

        @Test
        @DisplayName("should clear all revocations")
        void shouldClearAllRevocations() {
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));
            final var issuedBefore = Instant.now();

            repository.revoke("jti-1", expiresAt).await().atMost(Duration.ofSeconds(1));
            repository
                    .revokeAllForUser("user-1", issuedBefore, expiresAt)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            repository.clear();

            assertEquals(0, repository.getRevokedJtiCount());
            assertEquals(0, repository.getRevokedUserCount());
        }
    }
}
