package aussie.adapter.out.storage.memory;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.model.session.Session;
import aussie.core.port.out.SessionRepository;

/**
 * In-memory implementation of SessionRepository.
 *
 * <p>This implementation is intended for development and testing only.
 * Sessions are lost on restart and not shared across instances.
 *
 * <p><strong>Warning:</strong> When running multiple Aussie instances,
 * sticky sessions are required for this storage to work correctly.
 */
public class InMemorySessionRepository implements SessionRepository {

    private static final Logger LOG = Logger.getLogger(InMemorySessionRepository.class);

    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> userSessionIndex = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    public InMemorySessionRepository() {
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-cleanup");
            t.setDaemon(true);
            return t;
        });

        // Run cleanup every minute
        cleanupExecutor.scheduleAtFixedRate(this::cleanupExpiredSessions, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public Uni<Boolean> saveIfAbsent(Session session) {
        return Uni.createFrom().item(() -> {
            Session existing = sessions.putIfAbsent(session.id(), session);
            if (existing == null) {
                // Successfully inserted - update user index
                userSessionIndex.put(session.userId() + ":" + session.id(), session.id());
                LOG.debugf("Session created: %s for user %s", session.id(), session.userId());
                return true;
            }
            LOG.debugf("Session ID collision detected: %s", session.id());
            return false;
        });
    }

    @Override
    public Uni<Session> save(Session session) {
        return Uni.createFrom().item(() -> {
            sessions.put(session.id(), session);
            userSessionIndex.put(session.userId() + ":" + session.id(), session.id());
            return session;
        });
    }

    @Override
    public Uni<Optional<Session>> findById(String sessionId) {
        return Uni.createFrom().item(() -> {
            Session session = sessions.get(sessionId);
            if (session == null) {
                return Optional.empty();
            }
            // Note: Expiration checking is handled by the service layer.
            // The repository only handles data access.
            return Optional.of(session);
        });
    }

    @Override
    public Uni<Session> update(Session session) {
        return Uni.createFrom().item(() -> {
            sessions.put(session.id(), session);
            return session;
        });
    }

    @Override
    public Uni<Void> delete(String sessionId) {
        return Uni.createFrom().item(() -> {
            Session session = sessions.remove(sessionId);
            if (session != null) {
                userSessionIndex.remove(session.userId() + ":" + sessionId);
                LOG.debugf("Session deleted: %s", sessionId);
            }
            return null;
        });
    }

    @Override
    public Uni<Void> deleteByUserId(String userId) {
        return Uni.createFrom().item(() -> {
            String prefix = userId + ":";
            userSessionIndex.keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .forEach(key -> {
                        String sessionId = userSessionIndex.remove(key);
                        if (sessionId != null) {
                            sessions.remove(sessionId);
                            LOG.debugf("Session deleted for user logout: %s", sessionId);
                        }
                    });
            return null;
        });
    }

    @Override
    public Uni<Boolean> exists(String sessionId) {
        return Uni.createFrom().item(() -> sessions.containsKey(sessionId));
    }

    private void cleanupExpiredSessions() {
        Instant now = Instant.now();
        int removed = 0;

        for (var entry : sessions.entrySet()) {
            Session session = entry.getValue();
            if (session.expiresAt() != null && now.isAfter(session.expiresAt())) {
                sessions.remove(entry.getKey());
                userSessionIndex.remove(session.userId() + ":" + entry.getKey());
                removed++;
            }
        }

        if (removed > 0) {
            LOG.debugf("Cleaned up %d expired sessions", removed);
        }
    }

    /**
     * Shuts down the cleanup executor.
     */
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
     * Return the current session count (for testing).
     */
    public int getSessionCount() {
        return sessions.size();
    }
}
