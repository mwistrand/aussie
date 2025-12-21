package aussie.core.service.session;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.SessionConfig;
import aussie.core.model.session.Session;
import aussie.core.model.session.SessionInvalidatedEvent;
import aussie.core.port.in.SessionManagement;
import aussie.core.port.out.SessionRepository;
import aussie.core.service.auth.TokenRevocationService;

/**
 * Implementation of session management operations.
 *
 * <p>Handles session lifecycle including creation with collision retry,
 * validation, refresh (sliding expiration), and invalidation.
 *
 * <p>On logout-everywhere, tokens are also revoked via {@link TokenRevocationService}
 * to ensure immediate invalidation even if downstream services cache tokens.
 */
@ApplicationScoped
public class SessionService implements SessionManagement {

    private static final Logger LOG = Logger.getLogger(SessionService.class);

    private final SessionStorageProviderRegistry storageRegistry;
    private final SessionIdGenerator idGenerator;
    private final SessionConfig config;
    private final Event<SessionInvalidatedEvent> sessionInvalidatedEvent;
    private final TokenRevocationService tokenRevocationService;

    public SessionService(
            SessionStorageProviderRegistry storageRegistry,
            SessionIdGenerator idGenerator,
            SessionConfig config,
            Event<SessionInvalidatedEvent> sessionInvalidatedEvent,
            TokenRevocationService tokenRevocationService) {
        this.storageRegistry = storageRegistry;
        this.idGenerator = idGenerator;
        this.config = config;
        this.sessionInvalidatedEvent = sessionInvalidatedEvent;
        this.tokenRevocationService = tokenRevocationService;
    }

    @Override
    public Uni<Session> createSession(
            String userId,
            String issuer,
            Map<String, Object> claims,
            Set<String> permissions,
            String userAgent,
            String ipAddress) {

        Instant now = Instant.now();
        Instant expiresAt = now.plus(config.ttl());

        return createSessionWithRetry(userId, issuer, claims, permissions, userAgent, ipAddress, now, expiresAt, 0);
    }

    private Uni<Session> createSessionWithRetry(
            String userId,
            String issuer,
            Map<String, Object> claims,
            Set<String> permissions,
            String userAgent,
            String ipAddress,
            Instant createdAt,
            Instant expiresAt,
            int attempt) {

        int maxRetries = config.idGeneration().maxRetries();

        if (attempt >= maxRetries) {
            return Uni.createFrom()
                    .failure(new SessionCreationException(
                            "Failed to generate unique session ID after " + maxRetries + " attempts"));
        }

        String sessionId = idGenerator.generate();
        Session session = new Session(
                sessionId,
                userId,
                issuer,
                claims,
                permissions,
                createdAt,
                expiresAt,
                createdAt, // lastAccessedAt = createdAt initially
                userAgent,
                ipAddress);

        return getRepository().saveIfAbsent(session).flatMap(saved -> {
            if (saved) {
                LOG.infof("Session created: %s for user %s", sessionId, userId);
                return Uni.createFrom().item(session);
            }

            // Collision detected, retry with new ID
            LOG.warnf("Session ID collision detected (attempt %d/%d), retrying", attempt + 1, maxRetries);
            return createSessionWithRetry(
                    userId, issuer, claims, permissions, userAgent, ipAddress, createdAt, expiresAt, attempt + 1);
        });
    }

    @Override
    public Uni<Optional<Session>> getSession(String sessionId) {
        return getRepository().findById(sessionId).map(sessionOpt -> {
            if (sessionOpt.isEmpty()) {
                return Optional.empty();
            }

            Session session = sessionOpt.get();

            // Check if session is valid
            if (!session.isValid(config.idleTimeout())) {
                LOG.debugf("Session %s is invalid (expired or idle)", sessionId);
                // Async delete of invalid session
                getRepository()
                        .delete(sessionId)
                        .subscribe()
                        .with(
                                v -> LOG.debugf("Cleaned up invalid session: %s", sessionId),
                                e -> LOG.warnf("Failed to clean up session: %s", e.getMessage()));
                return Optional.empty();
            }

            return Optional.of(session);
        });
    }

    @Override
    public Uni<Optional<Session>> refreshSession(String sessionId) {
        return getSession(sessionId).flatMap(sessionOpt -> {
            if (sessionOpt.isEmpty()) {
                return Uni.createFrom().item(Optional.empty());
            }

            Session session = sessionOpt.get();
            Instant now = Instant.now();

            // Update lastAccessedAt
            Session updatedSession = session.withLastAccessedAt(now);

            // If sliding expiration is enabled, also update expiresAt
            if (config.slidingExpiration()) {
                updatedSession = updatedSession.withExpiresAt(now.plus(config.ttl()));
            }

            return getRepository().update(updatedSession).map(Optional::of);
        });
    }

    @Override
    public Uni<Void> invalidateSession(String sessionId) {
        LOG.infof("Invalidating session: %s", sessionId);
        return getRepository().delete(sessionId).invoke(() -> {
            // Fire event to notify WebSocket connections to close
            sessionInvalidatedEvent.fireAsync(SessionInvalidatedEvent.forSession(sessionId));
        });
    }

    @Override
    public Uni<Void> invalidateAllUserSessions(String userId) {
        LOG.infof("Invalidating all sessions for user: %s", userId);
        return getRepository()
                .deleteByUserId(userId)
                .flatMap(v -> {
                    // Revoke all tokens for the user to ensure immediate invalidation
                    // even if downstream services have cached tokens
                    if (tokenRevocationService.isEnabled()) {
                        return tokenRevocationService.revokeAllUserTokens(userId);
                    }
                    return Uni.createFrom().voidItem();
                })
                .invoke(() -> {
                    // Fire event to notify WebSocket connections to close
                    sessionInvalidatedEvent.fireAsync(SessionInvalidatedEvent.forUser(userId));
                });
    }

    private SessionRepository getRepository() {
        return storageRegistry.getRepository();
    }
}
