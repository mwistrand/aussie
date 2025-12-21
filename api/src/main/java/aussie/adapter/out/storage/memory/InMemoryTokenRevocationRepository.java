package aussie.adapter.out.storage.memory;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.port.out.TokenRevocationRepository;

/**
 * In-memory implementation of TokenRevocationRepository.
 *
 * <p>This implementation is intended for development and testing only.
 * Revocations are lost on restart and not shared across instances.
 *
 * <p><strong>Warning:</strong> Do not use in production with multiple instances.
 */
public class InMemoryTokenRevocationRepository implements TokenRevocationRepository {

    private static final Logger LOG = Logger.getLogger(InMemoryTokenRevocationRepository.class);

    private final ConcurrentMap<String, RevocationEntry> revokedJtis = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UserRevocationEntry> revokedUsers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    public InMemoryTokenRevocationRepository() {
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "revocation-cleanup");
            t.setDaemon(true);
            return t;
        });

        // Run cleanup every minute
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpired, 1, 1, TimeUnit.MINUTES);
        LOG.info("Initialized in-memory token revocation repository");
    }

    @Override
    public Uni<Void> revoke(String jti, Instant expiresAt) {
        return Uni.createFrom().item(() -> {
            revokedJtis.put(jti, new RevocationEntry(expiresAt));
            LOG.debugf("Revoked token in memory: %s (expires: %s)", jti, expiresAt);
            return null;
        });
    }

    @Override
    public Uni<Boolean> isRevoked(String jti) {
        return Uni.createFrom().item(() -> {
            var entry = revokedJtis.get(jti);
            if (entry == null) {
                return false;
            }
            // Check if expired
            if (Instant.now().isAfter(entry.expiresAt())) {
                revokedJtis.remove(jti);
                return false;
            }
            return true;
        });
    }

    @Override
    public Uni<Void> revokeAllForUser(String userId, Instant issuedBefore, Instant expiresAt) {
        return Uni.createFrom().item(() -> {
            revokedUsers.put(userId, new UserRevocationEntry(issuedBefore, expiresAt));
            LOG.debugf(
                    "Revoked all tokens for user in memory: %s (issuedBefore: %s, expires: %s)",
                    userId, issuedBefore, expiresAt);
            return null;
        });
    }

    @Override
    public Uni<Boolean> isUserRevoked(String userId, Instant issuedAt) {
        return Uni.createFrom().item(() -> {
            var entry = revokedUsers.get(userId);
            if (entry == null) {
                return false;
            }
            // Check if expired
            if (Instant.now().isAfter(entry.expiresAt())) {
                revokedUsers.remove(userId);
                return false;
            }
            // Check if token was issued before revocation cutoff
            return issuedAt.isBefore(entry.issuedBefore());
        });
    }

    @Override
    public Multi<String> streamAllRevokedJtis() {
        return Multi.createFrom().iterable(revokedJtis.keySet());
    }

    @Override
    public Multi<String> streamAllRevokedUsers() {
        return Multi.createFrom().iterable(revokedUsers.keySet());
    }

    private void cleanupExpired() {
        final var now = Instant.now();
        final var jtisBefore = revokedJtis.size();
        final var usersBefore = revokedUsers.size();

        revokedJtis.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
        revokedUsers.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));

        final var jtisRemoved = jtisBefore - revokedJtis.size();
        final var usersRemoved = usersBefore - revokedUsers.size();

        if (jtisRemoved > 0 || usersRemoved > 0) {
            LOG.debugf("Cleaned up %d expired JTI revocations and %d user revocations", jtisRemoved, usersRemoved);
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
     * Get the current count of revoked JTIs (for testing).
     */
    public int getRevokedJtiCount() {
        return revokedJtis.size();
    }

    /**
     * Get the current count of revoked users (for testing).
     */
    public int getRevokedUserCount() {
        return revokedUsers.size();
    }

    /**
     * Clear all revocations (for testing).
     */
    public void clear() {
        revokedJtis.clear();
        revokedUsers.clear();
    }

    private record RevocationEntry(Instant expiresAt) {}

    private record UserRevocationEntry(Instant issuedBefore, Instant expiresAt) {}
}
