package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.TokenRevocationConfig;
import aussie.core.port.out.RevocationEventPublisher;
import aussie.core.port.out.TokenRevocationRepository;

@DisplayName("TokenRevocationService")
@ExtendWith(MockitoExtension.class)
class TokenRevocationServiceTest {

    @Mock
    private TokenRevocationConfig config;

    @Mock
    private TokenRevocationConfig.BloomFilterConfig bloomFilterConfig;

    @Mock
    private TokenRevocationConfig.CacheConfig cacheConfig;

    @Mock
    private TokenRevocationConfig.PubSubConfig pubsubConfig;

    @Mock
    private TokenRevocationRepository repository;

    @Mock
    private RevocationEventPublisher eventPublisher;

    @Mock
    private RevocationBloomFilter bloomFilter;

    @Mock
    private RevocationCache cache;

    private TokenRevocationService service;

    @BeforeEach
    void setUp() {
        // Setup default config mocks with lenient to avoid unnecessary stubbing errors
        lenient().when(config.enabled()).thenReturn(true);
        lenient().when(config.checkThreshold()).thenReturn(Duration.ofSeconds(30));
        lenient().when(config.checkUserRevocation()).thenReturn(true);
        lenient().when(config.bloomFilter()).thenReturn(bloomFilterConfig);
        lenient().when(config.cache()).thenReturn(cacheConfig);
        lenient().when(config.pubsub()).thenReturn(pubsubConfig);
        lenient().when(cacheConfig.ttl()).thenReturn(Duration.ofMinutes(5));
        lenient().when(pubsubConfig.enabled()).thenReturn(true);

        service = new TokenRevocationService(config, repository, eventPublisher, bloomFilter, cache);
    }

    @Nested
    @DisplayName("isRevoked()")
    class IsRevokedTests {

        @Test
        @DisplayName("should return false when revocation is disabled")
        void shouldReturnFalseWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            service = new TokenRevocationService(config, repository, eventPublisher, bloomFilter, cache);

            final var jti = "test-jti";
            final var userId = "user-123";
            final var issuedAt = Instant.now().minus(Duration.ofHours(1));
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            final var result =
                    service.isRevoked(jti, userId, issuedAt, expiresAt).await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
        }

        @Test
        @DisplayName("should skip check for tokens expiring within threshold")
        void shouldSkipCheckForExpiringTokens() {
            when(config.checkThreshold()).thenReturn(Duration.ofMinutes(1));

            final var jti = "test-jti";
            final var userId = "user-123";
            final var issuedAt = Instant.now().minus(Duration.ofHours(1));
            final var expiresAt = Instant.now().plus(Duration.ofSeconds(30)); // Expires within threshold

            final var result =
                    service.isRevoked(jti, userId, issuedAt, expiresAt).await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
            verify(bloomFilter, never()).definitelyNotRevoked(anyString());
        }

        @Test
        @DisplayName("should return false when bloom filter says definitely not revoked")
        void shouldReturnFalseWhenBloomFilterSaysNotRevoked() {
            when(bloomFilter.isEnabled()).thenReturn(true);
            when(bloomFilter.definitelyNotRevoked("test-jti")).thenReturn(true);
            when(bloomFilter.userDefinitelyNotRevoked("user-123")).thenReturn(true);

            final var jti = "test-jti";
            final var userId = "user-123";
            final var issuedAt = Instant.now().minus(Duration.ofHours(1));
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            final var result =
                    service.isRevoked(jti, userId, issuedAt, expiresAt).await().atMost(Duration.ofSeconds(1));

            assertFalse(result);
            verify(repository, never()).isRevoked(anyString());
        }

        @Test
        @DisplayName("should check cache when bloom filter might contain token")
        void shouldCheckCacheWhenBloomFilterMightContain() {
            when(bloomFilter.isEnabled()).thenReturn(true);
            when(bloomFilter.definitelyNotRevoked("test-jti")).thenReturn(false); // Might be revoked
            when(cache.isEnabled()).thenReturn(true);
            when(cache.isJtiRevoked("test-jti")).thenReturn(java.util.Optional.of(true));

            final var jti = "test-jti";
            final var userId = "user-123";
            final var issuedAt = Instant.now().minus(Duration.ofHours(1));
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            final var result =
                    service.isRevoked(jti, userId, issuedAt, expiresAt).await().atMost(Duration.ofSeconds(1));

            assertTrue(result);
            verify(repository, never()).isRevoked(anyString());
        }

        @Test
        @DisplayName("should check repository when cache misses")
        void shouldCheckRepositoryWhenCacheMisses() {
            when(bloomFilter.isEnabled()).thenReturn(true);
            when(bloomFilter.definitelyNotRevoked("test-jti")).thenReturn(false);
            when(cache.isEnabled()).thenReturn(true);
            when(cache.isJtiRevoked("test-jti")).thenReturn(java.util.Optional.empty());
            when(cache.isUserRevoked(anyString(), any())).thenReturn(java.util.Optional.empty());
            when(repository.isRevoked("test-jti")).thenReturn(Uni.createFrom().item(true));

            final var jti = "test-jti";
            final var userId = "user-123";
            final var issuedAt = Instant.now().minus(Duration.ofHours(1));
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            final var result =
                    service.isRevoked(jti, userId, issuedAt, expiresAt).await().atMost(Duration.ofSeconds(1));

            assertTrue(result);
            verify(repository).isRevoked("test-jti");
        }

        @Test
        @DisplayName("should check user revocation when JTI not revoked")
        void shouldCheckUserRevocationWhenJtiNotRevoked() {
            when(bloomFilter.isEnabled()).thenReturn(true);
            when(bloomFilter.definitelyNotRevoked("test-jti")).thenReturn(false);
            when(bloomFilter.userDefinitelyNotRevoked("user-123")).thenReturn(false);
            when(cache.isEnabled()).thenReturn(true);
            when(cache.isJtiRevoked("test-jti")).thenReturn(java.util.Optional.empty());
            when(cache.isUserRevoked(anyString(), any())).thenReturn(java.util.Optional.empty());
            when(repository.isRevoked("test-jti")).thenReturn(Uni.createFrom().item(false));

            final var issuedAt = Instant.now().minus(Duration.ofHours(1));
            when(repository.isUserRevoked("user-123", issuedAt))
                    .thenReturn(Uni.createFrom().item(true));

            final var jti = "test-jti";
            final var userId = "user-123";
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            final var result =
                    service.isRevoked(jti, userId, issuedAt, expiresAt).await().atMost(Duration.ofSeconds(1));

            assertTrue(result);
            verify(repository).isUserRevoked("user-123", issuedAt);
        }
    }

    @Nested
    @DisplayName("revokeToken()")
    class RevokeTokenTests {

        @Test
        @DisplayName("should store revocation in repository")
        void shouldStoreRevocationInRepository() {
            final var jti = "test-jti";
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            when(repository.revoke(jti, expiresAt)).thenReturn(Uni.createFrom().voidItem());
            when(eventPublisher.publishJtiRevoked(jti, expiresAt))
                    .thenReturn(Uni.createFrom().voidItem());

            service.revokeToken(jti, expiresAt).await().atMost(Duration.ofSeconds(1));

            verify(repository).revoke(jti, expiresAt);
        }

        @Test
        @DisplayName("should update bloom filter on revocation")
        void shouldUpdateBloomFilterOnRevocation() {
            final var jti = "test-jti";
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            when(repository.revoke(jti, expiresAt)).thenReturn(Uni.createFrom().voidItem());
            when(eventPublisher.publishJtiRevoked(jti, expiresAt))
                    .thenReturn(Uni.createFrom().voidItem());

            service.revokeToken(jti, expiresAt).await().atMost(Duration.ofSeconds(1));

            verify(bloomFilter).addRevokedJti(jti);
        }

        @Test
        @DisplayName("should update cache on revocation")
        void shouldUpdateCacheOnRevocation() {
            final var jti = "test-jti";
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            when(repository.revoke(jti, expiresAt)).thenReturn(Uni.createFrom().voidItem());
            when(eventPublisher.publishJtiRevoked(jti, expiresAt))
                    .thenReturn(Uni.createFrom().voidItem());

            service.revokeToken(jti, expiresAt).await().atMost(Duration.ofSeconds(1));

            verify(cache).cacheJtiRevocation(jti, expiresAt);
        }

        @Test
        @DisplayName("should publish event when pubsub enabled")
        void shouldPublishEventWhenPubsubEnabled() {
            final var jti = "test-jti";
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            when(repository.revoke(jti, expiresAt)).thenReturn(Uni.createFrom().voidItem());
            when(eventPublisher.publishJtiRevoked(jti, expiresAt))
                    .thenReturn(Uni.createFrom().voidItem());

            service.revokeToken(jti, expiresAt).await().atMost(Duration.ofSeconds(1));

            verify(eventPublisher).publishJtiRevoked(jti, expiresAt);
        }

        @Test
        @DisplayName("should skip publish when pubsub disabled")
        void shouldSkipPublishWhenPubsubDisabled() {
            when(pubsubConfig.enabled()).thenReturn(false);
            service = new TokenRevocationService(config, repository, eventPublisher, bloomFilter, cache);

            final var jti = "test-jti";
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            when(repository.revoke(jti, expiresAt)).thenReturn(Uni.createFrom().voidItem());

            service.revokeToken(jti, expiresAt).await().atMost(Duration.ofSeconds(1));

            verify(eventPublisher, never()).publishJtiRevoked(anyString(), any());
        }

        @Test
        @DisplayName("should not revoke when disabled")
        void shouldNotRevokeWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            service = new TokenRevocationService(config, repository, eventPublisher, bloomFilter, cache);

            final var jti = "test-jti";
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            service.revokeToken(jti, expiresAt).await().atMost(Duration.ofSeconds(1));

            verify(repository, never()).revoke(anyString(), any());
        }
    }

    @Nested
    @DisplayName("revokeAllUserTokens()")
    class RevokeAllUserTokensTests {

        @Test
        @DisplayName("should store user revocation in repository")
        void shouldStoreUserRevocationInRepository() {
            final var userId = "user-123";
            final var issuedBefore = Instant.now();

            when(repository.revokeAllForUser(anyString(), any(), any()))
                    .thenReturn(Uni.createFrom().voidItem());
            when(eventPublisher.publishUserRevoked(anyString(), any(), any()))
                    .thenReturn(Uni.createFrom().voidItem());

            service.revokeAllUserTokens(userId, issuedBefore).await().atMost(Duration.ofSeconds(1));

            verify(repository).revokeAllForUser(anyString(), any(), any());
        }

        @Test
        @DisplayName("should update bloom filter with user")
        void shouldUpdateBloomFilterWithUser() {
            final var userId = "user-123";
            final var issuedBefore = Instant.now();

            when(repository.revokeAllForUser(anyString(), any(), any()))
                    .thenReturn(Uni.createFrom().voidItem());
            when(eventPublisher.publishUserRevoked(anyString(), any(), any()))
                    .thenReturn(Uni.createFrom().voidItem());

            service.revokeAllUserTokens(userId, issuedBefore).await().atMost(Duration.ofSeconds(1));

            verify(bloomFilter).addRevokedUser(userId);
        }

        @Test
        @DisplayName("should not revoke when user revocation disabled")
        void shouldNotRevokeWhenUserRevocationDisabled() {
            when(config.checkUserRevocation()).thenReturn(false);
            service = new TokenRevocationService(config, repository, eventPublisher, bloomFilter, cache);

            final var userId = "user-123";

            service.revokeAllUserTokens(userId).await().atMost(Duration.ofSeconds(1));

            verify(repository, never()).revokeAllForUser(anyString(), any(), any());
        }
    }
}
