package aussie.core.service.session;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.SessionConfig;
import aussie.core.model.auth.ValidatedIdentity;
import aussie.core.model.session.Session;
import aussie.core.model.session.SessionInvalidatedEvent;
import aussie.core.port.in.RoleManagement;
import aussie.core.port.in.SessionManagement;
import aussie.core.port.out.SessionRepository;
import aussie.core.service.auth.TokenRevocationService;
import aussie.core.service.auth.TokenTranslationService;
import aussie.core.util.SecureHash;

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
    private static final String UPSTREAM_EXPIRATION_CLAIM = "aussie.identity.upstream_exp";

    private final SessionStorageProviderRegistry storageRegistry;
    private final SessionIdGenerator idGenerator;
    private final SessionConfig config;
    private final Event<SessionInvalidatedEvent> sessionInvalidatedEvent;
    private final TokenRevocationService tokenRevocationService;
    private final TokenTranslationService tokenTranslationService;
    private final RoleManagement roleManagement;

    public SessionService(
            SessionStorageProviderRegistry storageRegistry,
            SessionIdGenerator idGenerator,
            SessionConfig config,
            Event<SessionInvalidatedEvent> sessionInvalidatedEvent,
            TokenRevocationService tokenRevocationService,
            TokenTranslationService tokenTranslationService,
            RoleManagement roleManagement) {
        this.storageRegistry = storageRegistry;
        this.idGenerator = idGenerator;
        this.config = config;
        this.sessionInvalidatedEvent = sessionInvalidatedEvent;
        this.tokenRevocationService = tokenRevocationService;
        this.tokenTranslationService = tokenTranslationService;
        this.roleManagement = roleManagement;
    }

    @Override
    public Uni<Session> createSession(ValidatedIdentity identity, String userAgent, String ipAddress) {
        if (!identity.expiresAt().isAfter(Instant.now())) {
            return Uni.createFrom().failure(new SessionCreationException("Validated identity has expired"));
        }
        return tokenTranslationService
                .translate(identity.issuer(), identity.subject(), identity.claims())
                .flatMap(translated -> roleManagement
                        .expandRoles(translated.roles())
                        .flatMap(rolePermissions -> {
                            final var now = Instant.now();
                            if (!identity.expiresAt().isAfter(now)) {
                                return Uni.createFrom()
                                        .failure(new SessionCreationException("Validated identity has expired"));
                            }
                            final var expiresAt = earlier(now.plus(config.ttl()), identity.expiresAt());
                            final var claims = new HashMap<>(identity.claims());
                            claims.put(
                                    UPSTREAM_EXPIRATION_CLAIM,
                                    identity.expiresAt().getEpochSecond());
                            final var permissions = new HashSet<>(translated.permissions());
                            permissions.addAll(rolePermissions);
                            return createSessionWithRetry(
                                    identity.subject(),
                                    identity.issuer(),
                                    claims,
                                    Set.copyOf(permissions),
                                    userAgent,
                                    ipAddress,
                                    now,
                                    expiresAt,
                                    0);
                        }));
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
                LOG.infof("Session created: hash=%s for user %s", SecureHash.truncatedSha256(sessionId, 8), userId);
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
                if (LOG.isDebugEnabled()) {
                    LOG.debugf(
                            "Session hash=%s is invalid (expired or idle)", SecureHash.truncatedSha256(sessionId, 8));
                }
                // Async delete of invalid session
                getRepository()
                        .delete(sessionId)
                        .subscribe()
                        .with(
                                v -> {
                                    if (LOG.isDebugEnabled()) {
                                        LOG.debugf(
                                                "Cleaned up invalid session: hash=%s",
                                                SecureHash.truncatedSha256(sessionId, 8));
                                    }
                                },
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
                final var upstreamExpiration = session.claims().get(UPSTREAM_EXPIRATION_CLAIM) instanceof Number value
                        ? Instant.ofEpochSecond(value.longValue())
                        : session.expiresAt();
                if (upstreamExpiration != null) {
                    updatedSession = updatedSession.withExpiresAt(earlier(now.plus(config.ttl()), upstreamExpiration));
                }
            }

            return getRepository().update(updatedSession).map(Optional::of);
        });
    }

    @Override
    public Uni<Void> invalidateSession(String sessionId) {
        LOG.infof("Invalidating session: hash=%s", SecureHash.truncatedSha256(sessionId, 8));
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

    private Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }
}
