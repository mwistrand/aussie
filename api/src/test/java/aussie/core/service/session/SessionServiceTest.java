package aussie.core.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.auth.DefaultTokenTranslatorProvider;
import aussie.adapter.out.storage.memory.InMemoryRevocationEventPublisher;
import aussie.adapter.out.storage.memory.InMemorySessionRepository;
import aussie.adapter.out.storage.memory.InMemoryTokenRevocationRepository;
import aussie.core.model.auth.ValidatedIdentity;
import aussie.core.model.session.Session;
import aussie.core.port.in.RoleManagement;
import aussie.core.port.in.SessionManagement.SessionCreationException;
import aussie.core.port.out.RevocationBloomFilter;
import aussie.core.port.out.RevocationCache;
import aussie.core.service.auth.TokenRevocationService;
import aussie.core.service.auth.TokenTranslationService;

@DisplayName("SessionService")
class SessionServiceTest {

    private SessionService sessionService;
    private InMemorySessionRepository repository;
    private TestSessionConfig config;
    private RoleManagement roleManagement;

    @BeforeEach
    void setUp() {
        repository = new InMemorySessionRepository();
        config = new TestSessionConfig();

        // Create a mock registry that returns our repository
        var registry = new TestSessionStorageProviderRegistry(repository);
        var idGenerator = new SessionIdGenerator();
        var sessionInvalidatedEvent = new NoOpEvent<aussie.core.model.session.SessionInvalidatedEvent>();

        // Create a mock token revocation service with mocked dependencies
        var revocationConfig = new TestTokenRevocationConfig();
        var revocationRepository = new InMemoryTokenRevocationRepository();
        var revocationEventPublisher = new InMemoryRevocationEventPublisher();
        var bloomFilter = mock(RevocationBloomFilter.class);
        var revocationCache = mock(RevocationCache.class);
        var tokenRevocationService = new TokenRevocationService(
                revocationConfig, revocationRepository, revocationEventPublisher, bloomFilter, revocationCache);
        var tokenTranslationService = mock(TokenTranslationService.class);
        roleManagement = mock(RoleManagement.class);
        var defaultTranslator = new DefaultTokenTranslatorProvider();
        when(tokenTranslationService.translate(anyString(), anyString(), any()))
                .thenAnswer(invocation -> defaultTranslator.translate(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
        when(roleManagement.expandRoles(any()))
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(Set.of()));

        sessionService = new SessionService(
                registry,
                idGenerator,
                config,
                sessionInvalidatedEvent,
                tokenRevocationService,
                tokenTranslationService,
                roleManagement);
    }

    private ValidatedIdentity identity(String subject, String issuer, Map<String, Object> claims) {
        return new ValidatedIdentity(
                "test-provider",
                subject,
                issuer,
                Set.of("test-audience"),
                Optional.empty(),
                Optional.of(subject + "-token"),
                claims,
                Optional.empty(),
                Instant.now().plus(Duration.ofDays(1)));
    }

    @Nested
    @DisplayName("createSession")
    class CreateSessionTests {

        @Test
        @DisplayName("should create session with all properties")
        void shouldCreateSessionWithAllProperties() {
            var session = sessionService
                    .createSession(
                            identity(
                                    "user123",
                                    "https://auth.example.com",
                                    Map.of("email", "user@example.com", "permissions", List.of("admin:read"))),
                            "Mozilla/5.0",
                            "192.168.1.1")
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNotNull(session.id());
            assertEquals("user123", session.userId());
            assertEquals("https://auth.example.com", session.issuer());
            assertEquals("user@example.com", session.claims().get("email"));
            assertEquals(Set.of("admin:read"), session.permissions());
            assertEquals("Mozilla/5.0", session.userAgent());
            assertEquals("192.168.1.1", session.ipAddress());
            assertNotNull(session.createdAt());
            assertNotNull(session.expiresAt());
            assertNotNull(session.lastAccessedAt());
        }

        @Test
        @DisplayName("should expand translated roles into session permissions")
        void shouldExpandTranslatedRoles() {
            when(roleManagement.expandRoles(Set.of("admin")))
                    .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(Set.of("admin:read")));

            final var session = sessionService
                    .createSession(identity("user123", "issuer", Map.of("roles", List.of("admin"))), null, null)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertEquals(Set.of("admin:read"), session.permissions());
        }

        @Test
        @DisplayName("should create session with TTL from config")
        void shouldCreateSessionWithTtlFromConfig() {
            var session = sessionService
                    .createSession(identity("user123", "issuer", Map.of()), null, null)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            var expectedExpiration = session.createdAt().plus(config.ttl());
            assertEquals(expectedExpiration, session.expiresAt());
        }

        @Test
        @DisplayName("should not outlive validated identity")
        void shouldNotOutliveValidatedIdentity() {
            final var identityExpiration = Instant.now().plus(Duration.ofMinutes(5));
            final var identity = new ValidatedIdentity(
                    "test-provider",
                    "user123",
                    "issuer",
                    Set.of("test-audience"),
                    Optional.empty(),
                    Optional.of("token-1"),
                    Map.of(),
                    Optional.empty(),
                    identityExpiration);

            final var session =
                    sessionService.createSession(identity, null, null).await().atMost(Duration.ofSeconds(5));

            assertEquals(identityExpiration, session.expiresAt());
        }

        @Test
        @DisplayName("should reject an expired validated identity")
        void shouldRejectExpiredIdentity() {
            final var expiredIdentity = new ValidatedIdentity(
                    "test-provider",
                    "user123",
                    "issuer",
                    Set.of("test-audience"),
                    Optional.empty(),
                    Optional.of("token-1"),
                    Map.of(),
                    Optional.empty(),
                    Instant.now().minusSeconds(1));

            assertThrows(SessionCreationException.class, () -> sessionService
                    .createSession(expiredIdentity, null, null)
                    .await()
                    .atMost(Duration.ofSeconds(5)));
        }

        @Test
        @DisplayName("should generate unique session IDs")
        void shouldGenerateUniqueSessionIds() {
            var session1 = sessionService
                    .createSession(identity("user1", "issuer", Map.of()), null, null)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            var session2 = sessionService
                    .createSession(identity("user2", "issuer", Map.of()), null, null)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertFalse(session1.id().equals(session2.id()));
        }

        @Test
        @DisplayName("should save session in repository")
        void shouldSaveSessionInRepository() {
            var session = sessionService
                    .createSession(identity("user123", "issuer", Map.of()), null, null)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            var found = repository.findById(session.id()).await().atMost(Duration.ofSeconds(5));
            assertTrue(found.isPresent());
            assertEquals(session.id(), found.get().id());
        }
    }

    @Nested
    @DisplayName("getSession")
    class GetSessionTests {

        @Test
        @DisplayName("should return session when valid")
        void shouldReturnSessionWhenValid() {
            var created = sessionService
                    .createSession(identity("user123", "issuer", Map.of()), null, null)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            var retrieved = sessionService.getSession(created.id()).await().atMost(Duration.ofSeconds(5));

            assertTrue(retrieved.isPresent());
            assertEquals(created.id(), retrieved.get().id());
        }

        @Test
        @DisplayName("should return empty for non-existent session")
        void shouldReturnEmptyForNonExistentSession() {
            var retrieved = sessionService.getSession("non-existent").await().atMost(Duration.ofSeconds(5));

            assertTrue(retrieved.isEmpty());
        }

        @Test
        @DisplayName("should return empty for expired session")
        void shouldReturnEmptyForExpiredSession() {
            // Create a session that's already expired
            var expiredSession = new Session(
                    "expired-session-id",
                    "user123",
                    "issuer",
                    Map.of(),
                    Set.of(),
                    Instant.now().minus(Duration.ofHours(2)),
                    Instant.now().minus(Duration.ofHours(1)), // Expired 1 hour ago
                    Instant.now().minus(Duration.ofHours(2)),
                    null,
                    null);
            repository.save(expiredSession).await().atMost(Duration.ofSeconds(5));

            var retrieved =
                    sessionService.getSession(expiredSession.id()).await().atMost(Duration.ofSeconds(5));

            assertTrue(retrieved.isEmpty());
        }

        @Test
        @DisplayName("should return empty for idle session")
        void shouldReturnEmptyForIdleSession() {
            // Create a session that's idle (not accessed within idle timeout)
            var idleSession = new Session(
                    "idle-session-id",
                    "user123",
                    "issuer",
                    Map.of(),
                    Set.of(),
                    Instant.now().minus(Duration.ofHours(2)),
                    Instant.now().plus(Duration.ofHours(6)), // Not expired
                    Instant.now().minus(Duration.ofHours(1)), // Last accessed 1 hour ago (idle timeout is 30m)
                    null,
                    null);
            repository.save(idleSession).await().atMost(Duration.ofSeconds(5));

            var retrieved = sessionService.getSession(idleSession.id()).await().atMost(Duration.ofSeconds(5));

            assertTrue(retrieved.isEmpty());
        }
    }

    @Nested
    @DisplayName("refreshSession")
    class RefreshSessionTests {

        @Test
        @DisplayName("should update lastAccessedAt")
        void shouldUpdateLastAccessedAt() {
            var created = sessionService
                    .createSession(identity("user123", "issuer", Map.of()), null, null)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            var originalLastAccessed = created.lastAccessedAt();

            // Wait a bit to ensure time difference
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            var refreshed = sessionService.refreshSession(created.id()).await().atMost(Duration.ofSeconds(5));

            assertTrue(refreshed.isPresent());
            assertTrue(refreshed.get().lastAccessedAt().isAfter(originalLastAccessed));
        }

        @Test
        @DisplayName("should extend expiresAt when sliding expiration is enabled")
        void shouldExtendExpiresAtWhenSlidingExpirationEnabled() {
            config.setSlidingExpiration(true);

            var created = sessionService
                    .createSession(identity("user123", "issuer", Map.of()), null, null)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            var originalExpiration = created.expiresAt();

            // Wait a bit to ensure time difference
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            var refreshed = sessionService.refreshSession(created.id()).await().atMost(Duration.ofSeconds(5));

            assertTrue(refreshed.isPresent());
            assertTrue(refreshed.get().expiresAt().isAfter(originalExpiration));
        }

        @Test
        @DisplayName("should not refresh beyond validated identity expiration")
        void shouldNotRefreshBeyondValidatedIdentityExpiration() {
            final var identityExpiration = Instant.now().plus(Duration.ofMinutes(5));
            final var identity = new ValidatedIdentity(
                    "test-provider",
                    "user123",
                    "issuer",
                    Set.of("test-audience"),
                    Optional.empty(),
                    Optional.of("token-1"),
                    Map.of(),
                    Optional.empty(),
                    identityExpiration);
            final var created =
                    sessionService.createSession(identity, null, null).await().atMost(Duration.ofSeconds(5));

            final var refreshed =
                    sessionService.refreshSession(created.id()).await().atMost(Duration.ofSeconds(5));

            assertTrue(refreshed.isPresent());
            assertFalse(refreshed.get().expiresAt().isAfter(identityExpiration));
        }

        @Test
        @DisplayName("should return empty for non-existent session")
        void shouldReturnEmptyForNonExistentSession() {
            var refreshed =
                    sessionService.refreshSession("non-existent").await().atMost(Duration.ofSeconds(5));

            assertTrue(refreshed.isEmpty());
        }
    }

    @Nested
    @DisplayName("invalidateSession")
    class InvalidateSessionTests {

        @Test
        @DisplayName("should delete session from repository")
        void shouldDeleteSessionFromRepository() {
            var created = sessionService
                    .createSession(identity("user123", "issuer", Map.of()), null, null)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            sessionService.invalidateSession(created.id()).await().atMost(Duration.ofSeconds(5));

            var found = repository.findById(created.id()).await().atMost(Duration.ofSeconds(5));
            assertTrue(found.isEmpty());
        }
    }

    @Nested
    @DisplayName("invalidateAllUserSessions")
    class InvalidateAllUserSessionsTests {

        @Test
        @DisplayName("should delete all sessions for user")
        void shouldDeleteAllSessionsForUser() {
            // Create multiple sessions for the same user
            sessionService
                    .createSession(identity("user123", "issuer", Map.of()), null, null)
                    .await()
                    .atMost(Duration.ofSeconds(5));
            sessionService
                    .createSession(identity("user123", "issuer", Map.of()), null, null)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            // Create a session for a different user
            var otherSession = sessionService
                    .createSession(identity("other-user", "issuer", Map.of()), null, null)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            sessionService.invalidateAllUserSessions("user123").await().atMost(Duration.ofSeconds(5));

            // Other user's session should still exist
            var otherFound = repository.findById(otherSession.id()).await().atMost(Duration.ofSeconds(5));
            assertTrue(otherFound.isPresent());
        }
    }

    /**
     * Test configuration for SessionService.
     */
    static class TestSessionConfig implements aussie.core.config.SessionConfig {
        private Duration ttl = Duration.ofHours(8);
        private Duration idleTimeout = Duration.ofMinutes(30);
        private boolean slidingExpiration = true;

        public void setSlidingExpiration(boolean value) {
            this.slidingExpiration = value;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public boolean publicCreationEnabled() {
            return false;
        }

        @Override
        public CookieConfig cookie() {
            return new CookieConfig() {
                @Override
                public String name() {
                    return "aussie_session";
                }

                @Override
                public String path() {
                    return "/";
                }

                @Override
                public java.util.Optional<String> domain() {
                    return java.util.Optional.empty();
                }

                @Override
                public boolean secure() {
                    return false;
                }

                @Override
                public boolean httpOnly() {
                    return true;
                }

                @Override
                public String sameSite() {
                    return "Lax";
                }
            };
        }

        @Override
        public Duration ttl() {
            return ttl;
        }

        @Override
        public Duration idleTimeout() {
            return idleTimeout;
        }

        @Override
        public boolean slidingExpiration() {
            return slidingExpiration;
        }

        @Override
        public IdGenerationConfig idGeneration() {
            return () -> 3;
        }

        @Override
        public StorageConfig storage() {
            return new StorageConfig() {
                @Override
                public String provider() {
                    return "memory";
                }

                @Override
                public RedisConfig redis() {
                    return () -> "aussie:session:";
                }
            };
        }

        @Override
        public JwsConfig jws() {
            return new JwsConfig() {
                @Override
                public boolean enabled() {
                    return false;
                }

                @Override
                public Duration ttl() {
                    return Duration.ofMinutes(5);
                }

                @Override
                public String issuer() {
                    return "aussie-gateway";
                }

                @Override
                public java.util.Optional<String> audience() {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.List<String> includeClaims() {
                    return java.util.List.of("sub", "email", "name", "roles");
                }
            };
        }
    }

    /**
     * Test registry that returns the provided repository.
     * Vetoed to prevent CDI from discovering it during integration tests.
     */
    @jakarta.enterprise.inject.Vetoed
    static class TestSessionStorageProviderRegistry extends SessionStorageProviderRegistry {
        private final InMemorySessionRepository testRepository;

        @SuppressWarnings("unchecked")
        TestSessionStorageProviderRegistry(InMemorySessionRepository repository) {
            super(mock(jakarta.enterprise.inject.Instance.class), mock(aussie.core.config.SessionConfig.class));
            this.testRepository = repository;
        }

        @Override
        public aussie.core.port.out.SessionRepository getRepository() {
            return testRepository;
        }
    }

    /**
     * Test configuration for TokenRevocationService.
     */
    static class TestTokenRevocationConfig implements aussie.core.config.TokenRevocationConfig {
        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public boolean checkUserRevocation() {
            return true;
        }

        @Override
        public BloomFilterConfig bloomFilter() {
            return new BloomFilterConfig() {
                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public int expectedInsertions() {
                    return 10000;
                }

                @Override
                public double falsePositiveProbability() {
                    return 0.01;
                }

                @Override
                public Duration rebuildInterval() {
                    return Duration.ofMinutes(5);
                }
            };
        }

        @Override
        public CacheConfig cache() {
            return new CacheConfig() {
                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public int maxSize() {
                    return 1000;
                }

                @Override
                public Duration ttl() {
                    return Duration.ofMinutes(5);
                }
            };
        }

        @Override
        public PubSubConfig pubsub() {
            return new PubSubConfig() {
                @Override
                public boolean enabled() {
                    return false;
                }

                @Override
                public String channel() {
                    return "test-revocation";
                }
            };
        }
    }

    /**
     * No-op Event implementation for testing.
     */
    static class NoOpEvent<T> implements Event<T> {
        @Override
        public void fire(T event) {
            // No-op
        }

        @Override
        public <U extends T> CompletionStage<U> fireAsync(U event) {
            return java.util.concurrent.CompletableFuture.completedFuture(event);
        }

        @Override
        public <U extends T> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
            return java.util.concurrent.CompletableFuture.completedFuture(event);
        }

        @Override
        public Event<T> select(java.lang.annotation.Annotation... qualifiers) {
            return this;
        }

        @Override
        public <U extends T> Event<U> select(Class<U> subtype, java.lang.annotation.Annotation... qualifiers) {
            return new NoOpEvent<>();
        }

        @Override
        public <U extends T> Event<U> select(TypeLiteral<U> subtype, java.lang.annotation.Annotation... qualifiers) {
            return new NoOpEvent<>();
        }
    }
}
