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

import aussie.core.model.auth.OidcAuthorizationTransaction;
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
    public Uni<Void> store(String state, OidcAuthorizationTransaction transaction, Duration ttl) {
        return Uni.createFrom().item(() -> {
            final var expiresAt = Instant.now().plus(ttl);
            challenges.put(state, new ChallengeEntry(transaction, expiresAt));
            LOG.debugf("Stored OIDC authorization transaction with TTL: %s", ttl);
            return null;
        });
    }

    @Override
    public Uni<Optional<OidcAuthorizationTransaction>> consume(String state) {
        return Uni.createFrom().item(() -> {
            final var entry = challenges.remove(state);
            if (entry == null) {
                LOG.debug("No OIDC authorization transaction found");
                return Optional.<OidcAuthorizationTransaction>empty();
            }

            // Check if expired
            if (Instant.now().isAfter(entry.expiresAt())) {
                LOG.debug("OIDC authorization transaction expired");
                return Optional.<OidcAuthorizationTransaction>empty();
            }

            LOG.debug("Consumed OIDC authorization transaction");
            return Optional.of(entry.transaction());
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

    private record ChallengeEntry(OidcAuthorizationTransaction transaction, Instant expiresAt) {}
}
