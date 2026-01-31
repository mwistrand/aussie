package aussie.core.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.storage.memory.InMemoryRevocationEventPublisher;
import aussie.adapter.out.storage.memory.InMemorySessionRepository;
import aussie.adapter.out.storage.memory.InMemoryTokenRevocationRepository;
import aussie.core.model.session.Session;
import aussie.core.service.auth.RevocationBloomFilter;
import aussie.core.service.auth.RevocationCache;
import aussie.core.service.auth.TokenRevocationService;

@DisplayName("SessionService")
class SessionServiceTest {

    private SessionService sessionService;
    private InMemorySessionRepository repository;
    private TestSessionConfig config;

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

        sessionService =
                new SessionService(registry, idGenerator, config, sessionInvalidatedEvent, tokenRevocationService);
    }

    @Nested
    @DisplayName("createSession")
    class CreateSessionTests {

        @Test
        @DisplayName("should create session with all properties")
        void shouldCreateSessionWithAllProperties() {
            var session = sessionService
                    .createSession(
                            "user123",
                            "https://auth.example.com",
                            Map.of("email", "user@example.com"),
                            Set.of("admin:read"),
                            "Mozilla/5.0",
                            "192.168.1.1")
                    .await()
                    .indefinitely();

            assertNotNull(session.id());
            assertEquals("user123", session.userId());
            assertEquals("https://auth.example.com", session.issuer());
            assertEquals("user@example.com", session.claims().get("email"));
            assertTrue(session.permissions().contains("admin:read"));
            assertEquals("Mozilla/5.0", session.userAgent());
            assertEquals("192.168.1.1", session.ipAddress());
            assertNotNull(session.createdAt());
            assertNotNull(session.expiresAt());
            assertNotNull(session.lastAccessedAt());
        }

        @Test
        @DisplayName("should create session with TTL from config")
        void shouldCreateSessionWithTtlFromConfig() {
            var session = sessionService
                    .createSession("user123", "issuer", Map.of(), Set.of(), null, null)
                    .await()
                    .indefinitely();

            var expectedExpiration = session.createdAt().plus(config.ttl());
            assertEquals(expectedExpiration, session.expiresAt());
        }

        @Test
        @DisplayName("should generate unique session IDs")
        void shouldGenerateUniqueSessionIds() {
            var session1 = sessionService
                    .createSession("user1", "issuer", Map.of(), Set.of(), null, null)
                    .await()
                    .indefinitely();

            var session2 = sessionService
                    .createSession("user2", "issuer", Map.of(), Set.of(), null, null)
                    .await()
                    .indefinitely();

            assertFalse(session1.id().equals(session2.id()));
        }

        @Test
        @DisplayName("should save session in repository")
        void shouldSaveSessionInRepository() {
            var session = sessionService
                    .createSession("user123", "issuer", Map.of(), Set.of(), null, null)
                    .await()
                    .indefinitely();

            var found = repository.findById(session.id()).await().indefinitely();
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
                    .createSession("user123", "issuer", Map.of(), Set.of(), null, null)
                    .await()
                    .indefinitely();

            var retrieved = sessionService.getSession(created.id()).await().indefinitely();

            assertTrue(retrieved.isPresent());
            assertEquals(created.id(), retrieved.get().id());
        }

        @Test
        @DisplayName("should return empty for non-existent session")
        void shouldReturnEmptyForNonExistentSession() {
            var retrieved = sessionService.getSession("non-existent").await().indefinitely();

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
            repository.save(expiredSession).await().indefinitely();

            var retrieved =
                    sessionService.getSession(expiredSession.id()).await().indefinitely();

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
            repository.save(idleSession).await().indefinitely();

            var retrieved = sessionService.getSession(idleSession.id()).await().indefinitely();

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
                    .createSession("user123", "issuer", Map.of(), Set.of(), null, null)
                    .await()
                    .indefinitely();

            var originalLastAccessed = created.lastAccessedAt();

            // Wait a bit to ensure time difference
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            var refreshed = sessionService.refreshSession(created.id()).await().indefinitely();

            assertTrue(refreshed.isPresent());
            assertTrue(refreshed.get().lastAccessedAt().isAfter(originalLastAccessed));
        }

        @Test
        @DisplayName("should extend expiresAt when sliding expiration is enabled")
        void shouldExtendExpiresAtWhenSlidingExpirationEnabled() {
            config.setSlidingExpiration(true);

            var created = sessionService
                    .createSession("user123", "issuer", Map.of(), Set.of(), null, null)
                    .await()
                    .indefinitely();

            var originalExpiration = created.expiresAt();

            // Wait a bit to ensure time difference
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            var refreshed = sessionService.refreshSession(created.id()).await().indefinitely();

            assertTrue(refreshed.isPresent());
            assertTrue(refreshed.get().expiresAt().isAfter(originalExpiration));
        }

        @Test
        @DisplayName("should return empty for non-existent session")
        void shouldReturnEmptyForNonExistentSession() {
            var refreshed =
                    sessionService.refreshSession("non-existent").await().indefinitely();

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
                    .createSession("user123", "issuer", Map.of(), Set.of(), null, null)
                    .await()
                    .indefinitely();

            sessionService.invalidateSession(created.id()).await().indefinitely();

            var found = repository.findById(created.id()).await().indefinitely();
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
                    .createSession("user123", "issuer", Map.of(), Set.of(), null, null)
                    .await()
                    .indefinitely();
            sessionService
                    .createSession("user123", "issuer", Map.of(), Set.of(), null, null)
                    .await()
                    .indefinitely();

            // Create a session for a different user
            var otherSession = sessionService
                    .createSession("other-user", "issuer", Map.of(), Set.of(), null, null)
                    .await()
                    .indefinitely();

            sessionService.invalidateAllUserSessions("user123").await().indefinitely();

            // Other user's session should still exist
            var otherFound = repository.findById(otherSession.id()).await().indefinitely();
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
        public Duration checkThreshold() {
            return Duration.ofSeconds(30);
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
