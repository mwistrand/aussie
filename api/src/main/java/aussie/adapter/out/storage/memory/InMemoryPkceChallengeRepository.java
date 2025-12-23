package aussie.adapter.out.storage.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.port.out.PkceChallengeRepository;

/**
 * In-memory implementation of PKCE challenge storage.
 *
 * <p>This implementation is intended for development and testing only.
 * Challenges are lost on restart and not shared across instances.
 *
 * <p><strong>Warning:</strong> Do not use in production with multiple instances.
 */
public class InMemoryPkceChallengeRepository implements PkceChallengeRepository {

    private static final Logger LOG = Logger.getLogger(InMemoryPkceChallengeRepository.class);

    private final ConcurrentMap<String, ChallengeEntry> challenges = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    public InMemoryPkceChallengeRepository() {
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "pkce-challenge-cleanup");
            t.setDaemon(true);
            return t;
        });

        // Run cleanup every minute
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpired, 1, 1, TimeUnit.MINUTES);
        LOG.info("Initialized in-memory PKCE challenge repository");
    }

    @Override
    public Uni<Void> store(String state, String challenge, Duration ttl) {
        return Uni.createFrom().item(() -> {
            final var expiresAt = Instant.now().plus(ttl);
            challenges.put(state, new ChallengeEntry(challenge, expiresAt));
            LOG.debugf("Stored PKCE challenge for state: %s with TTL: %s", state, ttl);
            return null;
        });
    }

    @Override
    public Uni<Optional<String>> consumeChallenge(String state) {
        return Uni.createFrom().item(() -> {
            final var entry = challenges.remove(state);
            if (entry == null) {
                LOG.debugf("No PKCE challenge found for state: %s", state);
                return Optional.<String>empty();
            }

            // Check if expired
            if (Instant.now().isAfter(entry.expiresAt())) {
                LOG.debugf("PKCE challenge expired for state: %s", state);
                return Optional.<String>empty();
            }

            LOG.debugf("Consumed PKCE challenge for state: %s", state);
            return Optional.of(entry.challenge());
        });
    }

    private void cleanupExpired() {
        final var now = Instant.now();
        final var before = challenges.size();

        challenges.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));

        final var removed = before - challenges.size();
        if (removed > 0) {
            LOG.debugf("Cleaned up %d expired PKCE challenge entries", removed);
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
     * Get the current count of stored challenges (for testing).
     */
    public int getChallengeCount() {
        return challenges.size();
    }

    /**
     * Clear all entries (for testing).
     */
    public void clear() {
        challenges.clear();
    }

    private record ChallengeEntry(String challenge, Instant expiresAt) {}
}
