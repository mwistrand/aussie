package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.TokenRevocationConfig;

@DisplayName("RevocationCache")
@ExtendWith(MockitoExtension.class)
class RevocationCacheTest {

    @Mock
    private TokenRevocationConfig config;

    @Mock
    private TokenRevocationConfig.CacheConfig cacheConfig;

    private RevocationCache cache;

    @BeforeEach
    void setUp() {
        when(config.enabled()).thenReturn(true);
        when(config.cache()).thenReturn(cacheConfig);
        when(cacheConfig.enabled()).thenReturn(true);
        when(cacheConfig.maxSize()).thenReturn(1000);
        when(cacheConfig.ttl()).thenReturn(Duration.ofMinutes(5));

        cache = new RevocationCache(config);
        cache.init();
    }

    @Nested
    @DisplayName("isJtiRevoked()")
    class IsJtiRevokedTests {

        @Test
        @DisplayName("should return empty for uncached JTI")
        void shouldReturnEmptyForUncachedJti() {
            final var result = cache.isJtiRevoked("unknown-jti");

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return true for cached revoked JTI")
        void shouldReturnTrueForCachedRevokedJti() {
            final var jti = "revoked-jti";
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            cache.cacheJtiRevocation(jti, expiresAt);

            final var result = cache.isJtiRevoked(jti);

            assertTrue(result.isPresent());
            assertTrue(result.get());
        }

        @Test
        @DisplayName("should return empty after invalidation")
        void shouldReturnEmptyAfterInvalidation() {
            final var jti = "revoked-jti";
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            cache.cacheJtiRevocation(jti, expiresAt);
            cache.invalidateJti(jti);

            final var result = cache.isJtiRevoked(jti);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty for expired entry")
        void shouldReturnEmptyForExpiredEntry() {
            final var jti = "expired-jti";
            final var expiresAt = Instant.now().minus(Duration.ofSeconds(1)); // Already expired

            cache.cacheJtiRevocation(jti, expiresAt);

            final var result = cache.isJtiRevoked(jti);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("isUserRevoked()")
    class IsUserRevokedTests {

        @Test
        @DisplayName("should return empty for uncached user")
        void shouldReturnEmptyForUncachedUser() {
            final var result = cache.isUserRevoked("unknown-user", Instant.now());

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return true for token issued before revocation cutoff")
        void shouldReturnTrueForTokenIssuedBeforeCutoff() {
            final var userId = "user-123";
            final var issuedBefore = Instant.now();
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            cache.cacheUserRevocation(userId, issuedBefore, expiresAt);

            // Token issued before the cutoff
            final var issuedAt = issuedBefore.minus(Duration.ofMinutes(5));
            final var result = cache.isUserRevoked(userId, issuedAt);

            assertTrue(result.isPresent());
            assertTrue(result.get());
        }

        @Test
        @DisplayName("should return empty for token issued after revocation cutoff")
        void shouldReturnEmptyForTokenIssuedAfterCutoff() {
            final var userId = "user-123";
            final var issuedBefore = Instant.now().minus(Duration.ofHours(1));
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            cache.cacheUserRevocation(userId, issuedBefore, expiresAt);

            // Token issued after the cutoff
            final var issuedAt = Instant.now();
            final var result = cache.isUserRevoked(userId, issuedAt);

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("invalidateAll()")
    class InvalidateAllTests {

        @Test
        @DisplayName("should clear all cached entries")
        void shouldClearAllCachedEntries() {
            final var expiresAt = Instant.now().plus(Duration.ofHours(1));

            cache.cacheJtiRevocation("jti-1", expiresAt);
            cache.cacheJtiRevocation("jti-2", expiresAt);
            cache.cacheUserRevocation("user-1", Instant.now(), expiresAt);

            cache.invalidateAll();

            assertTrue(cache.isJtiRevoked("jti-1").isEmpty());
            assertTrue(cache.isJtiRevoked("jti-2").isEmpty());
            assertTrue(cache.isUserRevoked("user-1", Instant.now().minus(Duration.ofHours(1)))
                    .isEmpty());
        }
    }

    @Nested
    @DisplayName("isEnabled()")
    class IsEnabledTests {

        @Test
        @DisplayName("should return true when enabled")
        void shouldReturnTrueWhenEnabled() {
            assertTrue(cache.isEnabled());
        }

        @Test
        @DisplayName("should return false when disabled")
        void shouldReturnFalseWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            final var disabledCache = new RevocationCache(config);
            disabledCache.init();

            assertFalse(disabledCache.isEnabled());
        }

        @Test
        @DisplayName("should return false when cache specifically disabled")
        void shouldReturnFalseWhenCacheDisabled() {
            when(cacheConfig.enabled()).thenReturn(false);
            final var disabledCache = new RevocationCache(config);
            disabledCache.init();

            assertFalse(disabledCache.isEnabled());
        }
    }
}
