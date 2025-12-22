package aussie.adapter.out.storage.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PreDestroy;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.spi.FailedAttemptRepository;

/**
 * In-memory implementation of FailedAttemptRepository.
 *
 * <p>
 * This implementation is intended for development and testing only.
 * Failed attempts and lockouts are lost on restart and not shared across
 * instances.
 *
 * <p>
 * <strong>Warning:</strong> Do not use in production with multiple instances.
 */
public class InMemoryFailedAttemptRepository implements FailedAttemptRepository {

    private static final Logger LOG = Logger.getLogger(InMemoryFailedAttemptRepository.class);

    private final ConcurrentMap<String, FailedAttemptEntry> failedAttempts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LockoutEntry> lockouts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> lockoutCounts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    public InMemoryFailedAttemptRepository() {
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "failed-attempt-cleanup");
            t.setDaemon(true);
            return t;
        });

        // Run cleanup every minute
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpired, 1, 1, TimeUnit.MINUTES);
        LOG.info("Initialized in-memory failed attempt repository");
    }

    @Override
    public Uni<Long> recordFailedAttempt(String key, Duration windowDuration) {
        return Uni.createFrom().item(() -> {
            final var now = Instant.now();
            final var expiresAt = now.plus(windowDuration);

            final var entry = failedAttempts.compute(key, (k, existing) -> {
                if (existing == null || now.isAfter(existing.expiresAt())) {
                    // New entry or expired, start fresh
                    return new FailedAttemptEntry(1, expiresAt);
                }
                // Increment existing and extend expiry
                return new FailedAttemptEntry(existing.count() + 1, expiresAt);
            });

            LOG.debugf("Recorded failed attempt for %s: count=%d, expires=%s", key, entry.count(), entry.expiresAt());
            return (long) entry.count();
        });
    }

    @Override
    public Uni<Long> getFailedAttemptCount(String key) {
        return Uni.createFrom().item(() -> {
            final var entry = failedAttempts.get(key);
            if (entry == null) {
                return 0L;
            }
            // Check if expired
            if (Instant.now().isAfter(entry.expiresAt())) {
                failedAttempts.remove(key);
                return 0L;
            }
            return (long) entry.count();
        });
    }

    @Override
    public Uni<Void> clearFailedAttempts(String key) {
        return Uni.createFrom().item(() -> {
            failedAttempts.remove(key);
            LOG.debugf("Cleared failed attempts for %s", key);
            return null;
        });
    }

    @Override
    public Uni<Void> recordLockout(String key, Duration lockoutDuration, String reason) {
        return Uni.createFrom().item(() -> {
            final var now = Instant.now();
            final var expiresAt = now.plus(lockoutDuration);

            lockoutCounts.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();

            final var attemptEntry = failedAttempts.get(key);
            final var failedCount = attemptEntry != null ? attemptEntry.count() : 0;

            lockouts.put(key, new LockoutEntry(now, expiresAt, reason, failedCount));
            LOG.infof("Recorded lockout for %s: reason=%s, expires=%s", key, reason, expiresAt);
            return null;
        });
    }

    @Override
    public Uni<Boolean> isLockedOut(String key) {
        return Uni.createFrom().item(() -> {
            final var entry = lockouts.get(key);
            if (entry == null) {
                return false;
            }
            // Check if expired
            if (Instant.now().isAfter(entry.expiresAt())) {
                lockouts.remove(key);
                return false;
            }
            return true;
        });
    }

    @Override
    public Uni<Instant> getLockoutExpiry(String key) {
        return Uni.createFrom().item(() -> {
            final var entry = lockouts.get(key);
            if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
                return null;
            }
            return entry.expiresAt();
        });
    }

    @Override
    public Uni<Void> clearLockout(String key) {
        return Uni.createFrom().item(() -> {
            lockouts.remove(key);
            LOG.infof("Cleared lockout for %s", key);
            return null;
        });
    }

    @Override
    public Uni<Integer> getLockoutCount(String key) {
        return Uni.createFrom().item(() -> {
            final var counter = lockoutCounts.get(key);
            return counter != null ? (int) counter.get() : 0;
        });
    }

    @Override
    public Multi<LockoutInfo> streamAllLockouts() {
        final var now = Instant.now();
        return Multi.createFrom()
                .iterable(lockouts.entrySet())
                .filter(entry -> !now.isAfter(entry.getValue().expiresAt()))
                .map(entry -> {
                    final var lockoutEntry = entry.getValue();
                    final var counter = lockoutCounts.get(entry.getKey());
                    final var lockoutCount = counter != null ? (int) counter.get() : 1;

                    return new LockoutInfo(
                            entry.getKey(),
                            lockoutEntry.lockedAt(),
                            lockoutEntry.expiresAt(),
                            lockoutEntry.reason(),
                            lockoutEntry.failedAttempts(),
                            lockoutCount);
                });
    }

    private void cleanupExpired() {
        final var now = Instant.now();
        final var attemptsBefore = failedAttempts.size();
        final var lockoutsBefore = lockouts.size();
        final var countsBefore = lockoutCounts.size();

        failedAttempts.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
        lockouts.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
        // Clean up lockout counts for keys with no active lockout or failed attempts
        lockoutCounts
                .entrySet()
                .removeIf(
                        entry -> !lockouts.containsKey(entry.getKey()) && !failedAttempts.containsKey(entry.getKey()));

        final var attemptsRemoved = attemptsBefore - failedAttempts.size();
        final var lockoutsRemoved = lockoutsBefore - lockouts.size();
        final var countsRemoved = countsBefore - lockoutCounts.size();

        if (attemptsRemoved > 0 || lockoutsRemoved > 0 || countsRemoved > 0) {
            LOG.debugf(
                    "Cleaned up %d expired failed attempt entries, %d lockout entries, and %d lockout counts",
                    attemptsRemoved, lockoutsRemoved, countsRemoved);
        }
    }

    /**
     * Shuts down the cleanup executor.
     */
    @PreDestroy
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Get the current count of tracked keys (for testing).
     */
    public int getFailedAttemptKeyCount() {
        return failedAttempts.size();
    }

    /**
     * Get the current count of lockouts (for testing).
     */
    public int getLockoutKeyCount() {
        return lockouts.size();
    }

    /**
     * Clear all entries (for testing).
     */
    public void clear() {
        failedAttempts.clear();
        lockouts.clear();
        lockoutCounts.clear();
    }

    private record FailedAttemptEntry(int count, Instant expiresAt) {}

    private record LockoutEntry(Instant lockedAt, Instant expiresAt, String reason, int failedAttempts) {}
}
