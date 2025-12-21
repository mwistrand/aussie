package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import aussie.core.config.TokenRevocationConfig;
import aussie.core.port.out.RevocationEventPublisher;
import aussie.core.port.out.TokenRevocationRepository;

/**
 * Integration tests for TokenRevocationService using real bloom filter and cache.
 *
 * <p>These tests verify the end-to-end flow of token revocation and checking,
 * using real {@link RevocationBloomFilter} and {@link RevocationCache} instances
 * while mocking only external dependencies (repository, event publisher).
 */
@DisplayName("TokenRevocationService Integration")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TokenRevocationServiceIntegrationTest {

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
    private Vertx vertx;

    private RevocationBloomFilter bloomFilter;
    private RevocationCache cache;
    private TokenRevocationService service;

    @BeforeEach
    void setUp() throws Exception {
        // Configure revocation as enabled
        lenient().when(config.enabled()).thenReturn(true);
        lenient().when(config.checkThreshold()).thenReturn(Duration.ofSeconds(30));
        lenient().when(config.checkUserRevocation()).thenReturn(true);
        lenient().when(config.bloomFilter()).thenReturn(bloomFilterConfig);
        lenient().when(config.cache()).thenReturn(cacheConfig);
        lenient().when(config.pubsub()).thenReturn(pubsubConfig);

        // Configure bloom filter
        lenient().when(bloomFilterConfig.enabled()).thenReturn(true);
        lenient().when(bloomFilterConfig.expectedInsertions()).thenReturn(1000);
        lenient().when(bloomFilterConfig.falsePositiveProbability()).thenReturn(0.001);
        lenient().when(bloomFilterConfig.rebuildInterval()).thenReturn(Duration.ofHours(1));

        // Configure cache
        lenient().when(cacheConfig.enabled()).thenReturn(true);
        lenient().when(cacheConfig.maxSize()).thenReturn(1000);
        lenient().when(cacheConfig.ttl()).thenReturn(Duration.ofMinutes(5));

        // Disable pub/sub for simplicity
        lenient().when(pubsubConfig.enabled()).thenReturn(false);

        // Repository returns empty streams initially
        lenient()
                .when(repository.streamAllRevokedJtis())
                .thenReturn(Multi.createFrom().empty());
        lenient()
                .when(repository.streamAllRevokedUsers())
                .thenReturn(Multi.createFrom().empty());

        // Create real bloom filter and cache
        bloomFilter = new RevocationBloomFilter(config, repository, eventPublisher, vertx);
        cache = new RevocationCache(config);

        // Initialize via reflection (simulating @PostConstruct)
        invokePostConstruct(cache);
        bloomFilter.rebuildFilters().await().atMost(Duration.ofSeconds(5));

        // Create service with real bloom filter and cache
        service = new TokenRevocationService(config, repository, eventPublisher, bloomFilter, cache);
    }

    private void invokePostConstruct(Object target) throws Exception {
        for (Method method : target.getClass().getDeclaredMethods()) {
            if (method.isAnnotationPresent(jakarta.annotation.PostConstruct.class)) {
                method.setAccessible(true);
                method.invoke(target);
                return;
            }
        }
    }

    @Nested
    @DisplayName("End-to-end revocation flow")
    class EndToEndRevocationTests {

        @Test
        @DisplayName("should detect revoked token after revokeToken() is called")
        void shouldDetectRevokedTokenAfterRevocation() {
            final var jti = "token-to-revoke";
            final var userId = "user-123";
            final var issuedAt = Instant.now().minus(Duration.ofHours(1));
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            // Mock repository behavior
            when(repository.revoke(anyString(), any()))
                    .thenReturn(Uni.createFrom().voidItem());
            when(repository.isRevoked(jti)).thenReturn(Uni.createFrom().item(true));

            // Verify token is initially not revoked (bloom filter says definitely not)
            assertTrue(bloomFilter.definitelyNotRevoked(jti), "Token should initially be definitely not revoked");

            // Revoke the token
            service.revokeToken(jti, expiresAt).await().atMost(Duration.ofSeconds(1));

            // Verify bloom filter now says "might be revoked"
            assertFalse(
                    bloomFilter.definitelyNotRevoked(jti), "Bloom filter should now indicate token might be revoked");

            // Verify cache has the revocation
            assertTrue(cache.isJtiRevoked(jti).orElse(false), "Cache should indicate token is revoked");

            // Full service check should return revoked
            final var isRevoked =
                    service.isRevoked(jti, userId, issuedAt, expiresAt).await().atMost(Duration.ofSeconds(1));

            assertTrue(isRevoked, "Service should report token as revoked");
        }

        @Test
        @DisplayName("should detect user revocation after revokeAllUserTokens() is called")
        void shouldDetectUserRevocationAfterRevocation() {
            final var jti = "user-token";
            final var userId = "user-to-revoke";
            // Token was issued 2 hours ago
            final var tokenIssuedAt = Instant.now().minus(Duration.ofHours(2));
            // Revoke all tokens issued before 1 hour ago (includes our token)
            final var revokeIssuedBefore = Instant.now().minus(Duration.ofHours(1));
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            // Mock repository behavior
            when(repository.revokeAllForUser(anyString(), any(), any()))
                    .thenReturn(Uni.createFrom().voidItem());
            when(repository.isRevoked(jti)).thenReturn(Uni.createFrom().item(false));
            when(repository.isUserRevoked(userId, tokenIssuedAt))
                    .thenReturn(Uni.createFrom().item(true));

            // Verify user is initially not revoked
            assertTrue(bloomFilter.userDefinitelyNotRevoked(userId), "User should initially be definitely not revoked");

            // Revoke all user tokens issued before the cutoff
            service.revokeAllUserTokens(userId, revokeIssuedBefore).await().atMost(Duration.ofSeconds(1));

            // Verify bloom filter now says "user might be revoked"
            assertFalse(
                    bloomFilter.userDefinitelyNotRevoked(userId),
                    "Bloom filter should now indicate user might be revoked");

            // Verify cache has the user revocation (token issued at 2h ago is before cutoff of 1h ago)
            assertTrue(
                    cache.isUserRevoked(userId, tokenIssuedAt).orElse(false), "Cache should indicate user is revoked");

            // Full service check should return revoked
            final var isRevoked = service.isRevoked(jti, userId, tokenIssuedAt, expiresAt)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(isRevoked, "Service should report user's token as revoked");
        }

        @Test
        @DisplayName("should not report non-revoked token as revoked")
        void shouldNotReportNonRevokedTokenAsRevoked() {
            final var jti = "valid-token";
            final var userId = "valid-user";
            final var issuedAt = Instant.now().minus(Duration.ofHours(1));
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            // Mock repository behavior - nothing is revoked
            when(repository.isRevoked(jti)).thenReturn(Uni.createFrom().item(false));
            when(repository.isUserRevoked(userId, issuedAt))
                    .thenReturn(Uni.createFrom().item(false));

            // Verify bloom filter says definitely not revoked
            assertTrue(bloomFilter.definitelyNotRevoked(jti));
            assertTrue(bloomFilter.userDefinitelyNotRevoked(userId));

            // Full service check should return not revoked
            final var isRevoked =
                    service.isRevoked(jti, userId, issuedAt, expiresAt).await().atMost(Duration.ofSeconds(1));

            assertFalse(isRevoked, "Service should report valid token as not revoked");
        }
    }

    @Nested
    @DisplayName("Bloom filter bypass scenarios")
    class BloomFilterBypassTests {

        @Test
        @DisplayName("should short-circuit to not revoked when bloom filter returns definitely not revoked")
        void shouldShortCircuitWhenBloomFilterSaysNotRevoked() {
            final var jti = "definitely-valid-token";
            final var userId = "valid-user";
            final var issuedAt = Instant.now().minus(Duration.ofHours(1));
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            // Bloom filter is empty, so definitelyNotRevoked should return true
            assertTrue(bloomFilter.definitelyNotRevoked(jti));

            // Full service check - should not even hit repository
            final var isRevoked =
                    service.isRevoked(jti, userId, issuedAt, expiresAt).await().atMost(Duration.ofSeconds(1));

            assertFalse(isRevoked);
            // Note: We can't easily verify repository wasn't called without additional mocking,
            // but the bloom filter optimization is working
        }

        @Test
        @DisplayName("should proceed to cache/repository when bloom filter says might be revoked")
        void shouldProceedToCacheWhenBloomFilterSaysMightBeRevoked() {
            final var jti = "maybe-revoked-token";
            final var userId = "user-123";
            final var issuedAt = Instant.now().minus(Duration.ofHours(1));
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            // Add to bloom filter (simulating a previous revocation or false positive)
            bloomFilter.addRevokedJti(jti);

            // Bloom filter now says "might be revoked"
            assertFalse(bloomFilter.definitelyNotRevoked(jti));

            // Repository says not actually revoked (false positive scenario)
            when(repository.isRevoked(jti)).thenReturn(Uni.createFrom().item(false));
            when(repository.isUserRevoked(userId, issuedAt))
                    .thenReturn(Uni.createFrom().item(false));

            final var isRevoked =
                    service.isRevoked(jti, userId, issuedAt, expiresAt).await().atMost(Duration.ofSeconds(1));

            assertFalse(isRevoked, "Token should not be revoked despite bloom filter false positive");
        }
    }

    @Nested
    @DisplayName("Cache behavior")
    class CacheBehaviorTests {

        @Test
        @DisplayName("should use cached result on subsequent checks")
        void shouldUseCachedResultOnSubsequentChecks() {
            final var jti = "cached-revoked-token";
            final var userId = "user-123";
            final var issuedAt = Instant.now().minus(Duration.ofHours(1));
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            // Add to bloom filter and cache directly
            bloomFilter.addRevokedJti(jti);
            cache.cacheJtiRevocation(jti, expiresAt);

            // Repository shouldn't be called if cache has the answer
            // (We don't need to mock repository here - cache should short-circuit)

            final var isRevoked =
                    service.isRevoked(jti, userId, issuedAt, expiresAt).await().atMost(Duration.ofSeconds(1));

            assertTrue(isRevoked, "Should return revoked from cache");
        }
    }

    @Nested
    @DisplayName("TTL threshold optimization")
    class TtlThresholdTests {

        @Test
        @DisplayName("should skip revocation check for tokens expiring within threshold")
        void shouldSkipCheckForSoonExpiringTokens() {
            final var jti = "expiring-soon";
            final var userId = "user-123";
            final var issuedAt = Instant.now().minus(Duration.ofHours(1));
            final var expiresAt = Instant.now().plus(Duration.ofSeconds(15)); // Within 30s threshold

            // Even if we add to bloom filter, should skip check
            bloomFilter.addRevokedJti(jti);

            final var isRevoked =
                    service.isRevoked(jti, userId, issuedAt, expiresAt).await().atMost(Duration.ofSeconds(1));

            assertFalse(isRevoked, "Should skip check and return not-revoked for soon-expiring token");
        }
    }
}
