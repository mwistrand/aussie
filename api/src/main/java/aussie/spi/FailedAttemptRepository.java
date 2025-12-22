package aussie.spi;

import java.time.Duration;
import java.time.Instant;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * SPI for tracking failed authentication attempts and lockouts.
 *
 * <p>Platform teams can provide custom implementations for their preferred
 * storage backend (e.g., Memcached via AWS ElastiCache, DynamoDB, etc.).
 * The repository must support atomic increment operations and automatic
 * TTL-based expiration.
 *
 * <h2>Implementation Requirements</h2>
 * <ul>
 *   <li>Increment operations MUST be atomic to prevent race conditions</li>
 *   <li>Entries MUST expire automatically based on the configured window</li>
 *   <li>All operations MUST be non-blocking (return Uni/Multi)</li>
 *   <li>Implementations SHOULD be thread-safe</li>
 *   <li>Implementations SHOULD handle connection failures gracefully</li>
 * </ul>
 *
 * <h2>Key Format</h2>
 * <p>Keys are composite identifiers with type prefixes:
 * <ul>
 *   <li>{@code ip:<address>} - Track by client IP address</li>
 *   <li>{@code user:<identifier>} - Track by username/email</li>
 *   <li>{@code apikey:<prefix>} - Track by API key prefix</li>
 * </ul>
 *
 * <h2>Registration</h2>
 * Platform teams register custom implementations via CDI:
 * <pre>{@code
 * @Alternative
 * @Priority(1)
 * @ApplicationScoped
 * public class MemcachedFailedAttemptRepository implements FailedAttemptRepository {
 *     // Custom implementation using AWS ElastiCache Memcached
 * }
 * }</pre>
 *
 * @see aussie.adapter.out.storage.redis.RedisFailedAttemptRepository
 * @see aussie.adapter.out.storage.memory.InMemoryFailedAttemptRepository
 * @see aussie.core.service.auth.AuthRateLimitService
 */
public interface FailedAttemptRepository {

    /**
     * Record a failed authentication attempt.
     *
     * <p>This operation MUST be atomic to prevent race conditions in
     * high-concurrency scenarios. The count should automatically expire
     * after the specified window duration.
     *
     * @param key composite key (e.g., "ip:192.168.1.1" or "user:john@example.com")
     * @param windowDuration how long to track this attempt before expiring
     * @return Uni with the new attempt count after incrementing
     */
    Uni<Long> recordFailedAttempt(String key, Duration windowDuration);

    /**
     * Get the current failed attempt count.
     *
     * @param key composite key to check
     * @return Uni with current count (0 if no attempts recorded or expired)
     */
    Uni<Long> getFailedAttemptCount(String key);

    /**
     * Clear failed attempts for a key.
     *
     * <p>Called after successful authentication to reset the counter,
     * or by administrators to manually unlock an account.
     *
     * @param key composite key to clear
     * @return Uni completing when cleared
     */
    Uni<Void> clearFailedAttempts(String key);

    /**
     * Record a lockout for an identifier.
     *
     * <p>When a key reaches the maximum failed attempts, it should be
     * locked out for the specified duration. During lockout, all
     * authentication attempts should be rejected.
     *
     * @param key composite key to lock out
     * @param lockoutDuration how long the lockout should last
     * @param reason human-readable reason for the lockout
     * @return Uni completing when lockout is recorded
     */
    Uni<Void> recordLockout(String key, Duration lockoutDuration, String reason);

    /**
     * Check if an identifier is currently locked out.
     *
     * @param key composite key to check
     * @return Uni with true if locked out, false otherwise
     */
    Uni<Boolean> isLockedOut(String key);

    /**
     * Get the lockout expiry time for a key.
     *
     * @param key composite key to check
     * @return Uni with the expiry instant, or empty if not locked out
     */
    Uni<Instant> getLockoutExpiry(String key);

    /**
     * Clear a lockout for a key.
     *
     * <p>Used by administrators to manually unlock an account.
     *
     * @param key composite key to unlock
     * @return Uni completing when lockout is cleared
     */
    Uni<Void> clearLockout(String key);

    /**
     * Get the lockout count for progressive lockout calculation.
     *
     * <p>Returns the number of times this key has been locked out,
     * used to calculate progressive lockout duration.
     *
     * @param key composite key to check
     * @return Uni with the lockout count
     */
    Uni<Integer> getLockoutCount(String key);

    /**
     * Stream all currently locked out keys.
     *
     * <p>Used by admin endpoints to list current lockouts.
     *
     * @return Multi streaming lockout info records
     */
    Multi<LockoutInfo> streamAllLockouts();

    /**
     * Information about a locked out key.
     *
     * @param key the locked out key
     * @param lockedAt when the lockout started
     * @param expiresAt when the lockout expires
     * @param reason the reason for lockout
     * @param failedAttempts number of failed attempts that triggered lockout
     * @param lockoutCount number of times this key has been locked out
     */
    record LockoutInfo(
            String key, Instant lockedAt, Instant expiresAt, String reason, int failedAttempts, int lockoutCount) {}
}
