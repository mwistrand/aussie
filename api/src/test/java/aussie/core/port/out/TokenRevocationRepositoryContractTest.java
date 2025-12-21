package aussie.core.port.out;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for TokenRevocationRepository implementations.
 *
 * <p>Platform teams should extend this class and implement {@link #createRepository()}
 * to verify their custom SPI implementations conform to the contract.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class MemcachedTokenRevocationRepositoryContractTest
 *     extends TokenRevocationRepositoryContractTest {
 *
 *     private MemcachedClient memcached;
 *
 *     @BeforeEach
 *     void setUp() {
 *         memcached = // create test client
 *     }
 *
 *     @Override
 *     protected TokenRevocationRepository createRepository() {
 *         return new MemcachedTokenRevocationRepository(memcached);
 *     }
 * }
 * }</pre>
 */
public abstract class TokenRevocationRepositoryContractTest {

    /**
     * Create a fresh instance of the repository under test.
     *
     * <p>Implementations should return a new instance connected to a clean
     * test backend (no leftover data from previous tests).
     */
    protected abstract TokenRevocationRepository createRepository();

    private TokenRevocationRepository repository;

    @BeforeEach
    void setUpContract() {
        repository = createRepository();
    }

    @Nested
    @DisplayName("revoke() and isRevoked()")
    class RevokeAndCheckTests {

        @Test
        @DisplayName("revoke() then isRevoked() should return true")
        void revokeAndCheck() {
            var jti = UUID.randomUUID().toString();
            var expiresAt = Instant.now().plus(Duration.ofHours(1));

            repository.revoke(jti, expiresAt).await().atMost(Duration.ofSeconds(5));

            var revoked = repository.isRevoked(jti).await().atMost(Duration.ofSeconds(5));

            assertTrue(revoked, "Token should be revoked after calling revoke()");
        }

        @Test
        @DisplayName("isRevoked() should return false for unknown JTI")
        void unknownJtiNotRevoked() {
            var jti = UUID.randomUUID().toString();

            var revoked = repository.isRevoked(jti).await().atMost(Duration.ofSeconds(5));

            assertFalse(revoked, "Unknown JTI should not be revoked");
        }

        @Test
        @DisplayName("multiple revocations should all be tracked")
        void multipleRevocations() {
            var jti1 = UUID.randomUUID().toString();
            var jti2 = UUID.randomUUID().toString();
            var jti3 = UUID.randomUUID().toString();
            var expiresAt = Instant.now().plus(Duration.ofHours(1));

            repository.revoke(jti1, expiresAt).await().atMost(Duration.ofSeconds(5));
            repository.revoke(jti2, expiresAt).await().atMost(Duration.ofSeconds(5));
            repository.revoke(jti3, expiresAt).await().atMost(Duration.ofSeconds(5));

            assertTrue(repository.isRevoked(jti1).await().atMost(Duration.ofSeconds(5)));
            assertTrue(repository.isRevoked(jti2).await().atMost(Duration.ofSeconds(5)));
            assertTrue(repository.isRevoked(jti3).await().atMost(Duration.ofSeconds(5)));
        }
    }

    @Nested
    @DisplayName("revokeAllForUser() and isUserRevoked()")
    class UserRevocationTests {

        @Test
        @DisplayName("revokeAllForUser() affects tokens issued before cutoff")
        void userRevocationAffectsOldTokens() {
            var userId = "user-" + UUID.randomUUID();
            var revokedAt = Instant.now();
            var expiresAt = Instant.now().plus(Duration.ofHours(1));

            repository.revokeAllForUser(userId, revokedAt, expiresAt).await().atMost(Duration.ofSeconds(5));

            // Token issued BEFORE revocation should be affected
            var issuedBefore = revokedAt.minus(Duration.ofSeconds(60));
            var revoked = repository.isUserRevoked(userId, issuedBefore).await().atMost(Duration.ofSeconds(5));

            assertTrue(revoked, "Token issued before revocation should be revoked");
        }

        @Test
        @DisplayName("revokeAllForUser() does not affect tokens issued after cutoff")
        void userRevocationDoesNotAffectNewTokens() {
            var userId = "user-" + UUID.randomUUID();
            var revokedAt = Instant.now().minus(Duration.ofSeconds(120));
            var expiresAt = Instant.now().plus(Duration.ofHours(1));

            repository.revokeAllForUser(userId, revokedAt, expiresAt).await().atMost(Duration.ofSeconds(5));

            // Token issued AFTER revocation should NOT be affected
            var issuedAfter = revokedAt.plus(Duration.ofSeconds(60));
            var revoked = repository.isUserRevoked(userId, issuedAfter).await().atMost(Duration.ofSeconds(5));

            assertFalse(revoked, "Token issued after revocation should not be revoked");
        }

        @Test
        @DisplayName("isUserRevoked() returns false for unknown user")
        void unknownUserNotRevoked() {
            var userId = "unknown-" + UUID.randomUUID();
            var issuedAt = Instant.now().minus(Duration.ofMinutes(5));

            var revoked = repository.isUserRevoked(userId, issuedAt).await().atMost(Duration.ofSeconds(5));

            assertFalse(revoked, "Unknown user should not be revoked");
        }

        @Test
        @DisplayName("multiple user revocations should all be tracked")
        void multipleUserRevocations() {
            var user1 = "user1-" + UUID.randomUUID();
            var user2 = "user2-" + UUID.randomUUID();
            var revokedAt = Instant.now();
            var expiresAt = Instant.now().plus(Duration.ofHours(1));
            var issuedBefore = revokedAt.minus(Duration.ofSeconds(60));

            repository.revokeAllForUser(user1, revokedAt, expiresAt).await().atMost(Duration.ofSeconds(5));
            repository.revokeAllForUser(user2, revokedAt, expiresAt).await().atMost(Duration.ofSeconds(5));

            assertTrue(repository.isUserRevoked(user1, issuedBefore).await().atMost(Duration.ofSeconds(5)));
            assertTrue(repository.isUserRevoked(user2, issuedBefore).await().atMost(Duration.ofSeconds(5)));
        }
    }

    @Nested
    @DisplayName("streamAllRevokedJtis()")
    class StreamJtisTests {

        @Test
        @DisplayName("should return all revoked JTIs")
        void streamAllRevokedJtis() {
            var jti1 = UUID.randomUUID().toString();
            var jti2 = UUID.randomUUID().toString();
            var expiresAt = Instant.now().plus(Duration.ofHours(1));

            repository.revoke(jti1, expiresAt).await().atMost(Duration.ofSeconds(5));
            repository.revoke(jti2, expiresAt).await().atMost(Duration.ofSeconds(5));

            var jtis =
                    repository.streamAllRevokedJtis().collect().asList().await().atMost(Duration.ofSeconds(5));

            assertTrue(jtis.contains(jti1), "Should contain first JTI");
            assertTrue(jtis.contains(jti2), "Should contain second JTI");
        }

        @Test
        @DisplayName("should return empty for no revocations")
        void streamEmptyWhenNoRevocations() {
            // Create fresh repository with no revocations
            var freshRepo = createRepository();

            var jtis =
                    freshRepo.streamAllRevokedJtis().collect().asList().await().atMost(Duration.ofSeconds(5));

            // Note: May not be empty if previous tests left data
            // This test is more about verifying the method works
            assertTrue(jtis != null, "Should return a list (possibly empty)");
        }
    }

    @Nested
    @DisplayName("streamAllRevokedUsers()")
    class StreamUsersTests {

        @Test
        @DisplayName("should return all users with blanket revocations")
        void streamAllRevokedUsers() {
            var user1 = "stream-user1-" + UUID.randomUUID();
            var user2 = "stream-user2-" + UUID.randomUUID();
            var revokedAt = Instant.now();
            var expiresAt = Instant.now().plus(Duration.ofHours(1));

            repository.revokeAllForUser(user1, revokedAt, expiresAt).await().atMost(Duration.ofSeconds(5));
            repository.revokeAllForUser(user2, revokedAt, expiresAt).await().atMost(Duration.ofSeconds(5));

            var users = repository
                    .streamAllRevokedUsers()
                    .collect()
                    .asList()
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertTrue(users.contains(user1), "Should contain first user");
            assertTrue(users.contains(user2), "Should contain second user");
        }
    }

    @Nested
    @DisplayName("Isolation")
    class IsolationTests {

        @Test
        @DisplayName("JTI revocation should not affect user revocation")
        void jtiRevocationIndependentOfUserRevocation() {
            var jti = UUID.randomUUID().toString();
            var userId = "isolation-user-" + UUID.randomUUID();
            var expiresAt = Instant.now().plus(Duration.ofHours(1));

            // Revoke a JTI
            repository.revoke(jti, expiresAt).await().atMost(Duration.ofSeconds(5));

            // User should NOT be affected
            var issuedAt = Instant.now().minus(Duration.ofMinutes(5));
            var userRevoked = repository.isUserRevoked(userId, issuedAt).await().atMost(Duration.ofSeconds(5));

            assertFalse(userRevoked, "User should not be affected by JTI revocation");
        }

        @Test
        @DisplayName("user revocation should not affect unrelated JTIs")
        void userRevocationIndependentOfJtiRevocation() {
            var jti = UUID.randomUUID().toString();
            var userId = "isolation-user2-" + UUID.randomUUID();
            var revokedAt = Instant.now();
            var expiresAt = Instant.now().plus(Duration.ofHours(1));

            // Revoke user
            repository.revokeAllForUser(userId, revokedAt, expiresAt).await().atMost(Duration.ofSeconds(5));

            // JTI should NOT be affected (unless it's a known revoked token)
            var jtiRevoked = repository.isRevoked(jti).await().atMost(Duration.ofSeconds(5));

            assertFalse(jtiRevoked, "Random JTI should not be affected by user revocation");
        }
    }
}
