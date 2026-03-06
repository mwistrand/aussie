package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.TokenTranslationConfig;
import aussie.core.model.auth.TranslatedClaims;
import aussie.core.model.auth.TranslationConfigSchema;
import aussie.core.port.out.TranslationMetrics;
import aussie.spi.TokenTranslatorProvider;

/**
 * Unit tests for TokenTranslationService.
 */
@DisplayName("TokenTranslationService")
@ExtendWith(MockitoExtension.class)
class TokenTranslationServiceTest {

    private static final String ISSUER = "https://issuer.example.com";
    private static final String SUBJECT = "user-123";

    @Mock
    private TokenTranslationConfig config;

    @Mock
    private TokenTranslationConfig.Cache cacheConfig;

    @Mock
    private TokenTranslatorProviderRegistry registry;

    @Mock
    private TokenTranslatorProvider provider;

    @Mock
    private TranslationMetrics metrics;

    private TokenTranslationService service;

    @BeforeEach
    void setUp() {
        lenient().when(config.cache()).thenReturn(cacheConfig);
        lenient().when(cacheConfig.ttlSeconds()).thenReturn(300);
        lenient().when(cacheConfig.maxSize()).thenReturn(10000L);
        lenient().when(registry.getProvider()).thenReturn(provider);
        lenient().when(provider.name()).thenReturn("test-provider");

        service = new TokenTranslationService(config, registry, metrics);
        service.init();
    }

    @Nested
    @DisplayName("translate")
    class Translate {

        @Test
        @DisplayName("should delegate to provider on cache miss")
        void shouldDelegateToProviderOnCacheMiss() {
            var claims = Map.<String, Object>of("jti", "unique-token-id");
            var expectedResult = new TranslatedClaims(Set.of("role"), Set.of("permission"), Map.of());

            when(provider.translate(ISSUER, SUBJECT, claims))
                    .thenReturn(Uni.createFrom().item(expectedResult));

            var result = service.translate(ISSUER, SUBJECT, claims).await().atMost(Duration.ofSeconds(5));

            assertEquals(expectedResult, result);
            verify(provider).translate(ISSUER, SUBJECT, claims);
        }

        @Test
        @DisplayName("should return cached result on cache hit")
        void shouldReturnCachedResultOnCacheHit() {
            var claims = Map.<String, Object>of("jti", "cached-token-id");
            var expectedResult = new TranslatedClaims(Set.of("role"), Set.of("permission"), Map.of());

            when(provider.translate(ISSUER, SUBJECT, claims))
                    .thenReturn(Uni.createFrom().item(expectedResult));

            // First call - cache miss
            service.translate(ISSUER, SUBJECT, claims).await().atMost(Duration.ofSeconds(5));
            // Second call - should hit cache
            var result = service.translate(ISSUER, SUBJECT, claims).await().atMost(Duration.ofSeconds(5));

            assertEquals(expectedResult, result);
            // Provider should only be called once
            verify(provider, times(1)).translate(ISSUER, SUBJECT, claims);
        }

        @Test
        @DisplayName("should use jti as cache key when present")
        void shouldUseJtiAsCacheKey() {
            var claims1 = Map.<String, Object>of("jti", "same-jti", "extra", "value1");
            var claims2 = Map.<String, Object>of("jti", "same-jti", "extra", "value2");
            var expectedResult = new TranslatedClaims(Set.of("role"), Set.of(), Map.of());

            when(provider.translate(any(), any(), any()))
                    .thenReturn(Uni.createFrom().item(expectedResult));

            // Both calls have same jti, so second should hit cache
            service.translate(ISSUER, SUBJECT, claims1).await().atMost(Duration.ofSeconds(5));
            service.translate(ISSUER, SUBJECT, claims2).await().atMost(Duration.ofSeconds(5));

            verify(provider, times(1)).translate(any(), any(), any());
        }

        @Test
        @DisplayName("should use composite key when jti not present")
        void shouldUseCompositeKeyWhenJtiNotPresent() {
            var claims = Map.<String, Object>of("iat", 1234567890);
            var expectedResult = new TranslatedClaims(Set.of("role"), Set.of(), Map.of());

            when(provider.translate(any(), any(), any()))
                    .thenReturn(Uni.createFrom().item(expectedResult));

            // Same issuer, subject, iat - should cache
            service.translate(ISSUER, SUBJECT, claims).await().atMost(Duration.ofSeconds(5));
            service.translate(ISSUER, SUBJECT, claims).await().atMost(Duration.ofSeconds(5));

            verify(provider, times(1)).translate(any(), any(), any());
        }

        @Test
        @DisplayName("should not cache across different tokens")
        void shouldNotCacheAcrossDifferentTokens() {
            var claims1 = Map.<String, Object>of("jti", "token-1");
            var claims2 = Map.<String, Object>of("jti", "token-2");
            var result1 = new TranslatedClaims(Set.of("admin"), Set.of(), Map.of());
            var result2 = new TranslatedClaims(Set.of("user"), Set.of(), Map.of());

            when(provider.translate(ISSUER, SUBJECT, claims1))
                    .thenReturn(Uni.createFrom().item(result1));
            when(provider.translate(ISSUER, SUBJECT, claims2))
                    .thenReturn(Uni.createFrom().item(result2));

            var actual1 = service.translate(ISSUER, SUBJECT, claims1).await().atMost(Duration.ofSeconds(5));
            var actual2 = service.translate(ISSUER, SUBJECT, claims2).await().atMost(Duration.ofSeconds(5));

            assertEquals(result1, actual1);
            assertEquals(result2, actual2);
            verify(provider, times(2)).translate(any(), any(), any());
        }

        @Test
        @DisplayName("should propagate provider failure")
        void shouldPropagateProviderFailure() {
            var claims = Map.<String, Object>of("jti", "failing-token");
            var expectedException = new RuntimeException("Provider failed");

            when(provider.translate(any(), any(), any()))
                    .thenReturn(Uni.createFrom().failure(expectedException));

            try {
                service.translate(ISSUER, SUBJECT, claims).await().atMost(Duration.ofSeconds(5));
            } catch (RuntimeException e) {
                assertEquals("Provider failed", e.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("isEnabled")
    class IsEnabled {

        @Test
        @DisplayName("should return true when enabled")
        void shouldReturnTrueWhenEnabled() {
            when(config.enabled()).thenReturn(true);

            assertTrue(service.isEnabled());
        }

        @Test
        @DisplayName("should return false when disabled")
        void shouldReturnFalseWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            assertFalse(service.isEnabled());
        }
    }

    @Nested
    @DisplayName("translate outcome")
    class TranslateOutcome {

        @Test
        @DisplayName("should record EMPTY outcome when roles and permissions are empty")
        void shouldRecordEmptyOutcome() {
            var claims = Map.<String, Object>of("jti", "empty-token");
            var emptyResult = new TranslatedClaims(Set.of(), Set.of(), Map.of());

            when(provider.translate(any(), any(), any()))
                    .thenReturn(Uni.createFrom().item(emptyResult));

            var result = service.translate(ISSUER, SUBJECT, claims).await().atMost(Duration.ofSeconds(5));

            assertEquals(emptyResult, result);
            verify(metrics).recordTranslation(any(), any(), any(long.class));
        }
    }

    @Nested
    @DisplayName("buildCacheKey edge cases")
    class BuildCacheKeyEdgeCases {

        @Test
        @DisplayName("should use default iat when not present in claims")
        void shouldUseDefaultIatWhenNotPresent() {
            var claims1 = Map.<String, Object>of("custom", "value");
            var claims2 = Map.<String, Object>of("custom", "value");
            var expectedResult = new TranslatedClaims(Set.of("role"), Set.of(), Map.of());

            when(provider.translate(any(), any(), any()))
                    .thenReturn(Uni.createFrom().item(expectedResult));

            // Both should use same cache key: issuer:subject:0
            service.translate(ISSUER, SUBJECT, claims1).await().atMost(Duration.ofSeconds(5));
            service.translate(ISSUER, SUBJECT, claims2).await().atMost(Duration.ofSeconds(5));

            verify(provider, times(1)).translate(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("translateWithConfig")
    class TranslateWithConfig {

        @Test
        @DisplayName("should translate using provided schema without caching")
        void shouldTranslateWithSchema() {
            var schema = new TranslationConfigSchema(
                    1,
                    List.of(new TranslationConfigSchema.ClaimSource(
                            "roles", "roles", TranslationConfigSchema.ClaimSource.ClaimType.ARRAY)),
                    List.of(),
                    new TranslationConfigSchema.Mappings(Map.of("admin", List.of("admin.*")), Map.of()),
                    null);
            var claims = Map.<String, Object>of("roles", List.of("admin"));

            var result = service.translateWithConfig(schema, ISSUER, SUBJECT, claims)
                    .await()
                    .atMost(Duration.ofSeconds(5));

            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("introspection methods")
    class IntrospectionMethods {

        @Test
        @DisplayName("should return cache size")
        void shouldReturnCacheSize() {
            assertEquals(0, service.getCacheSize());
        }

        @Test
        @DisplayName("should return cache TTL seconds")
        void shouldReturnCacheTtlSeconds() {
            assertEquals(300, service.getCacheTtlSeconds());
        }

        @Test
        @DisplayName("should return cache max size")
        void shouldReturnCacheMaxSize() {
            assertEquals(10000L, service.getCacheMaxSize());
        }

        @Test
        @DisplayName("should return active provider name")
        void shouldReturnActiveProviderName() {
            assertEquals("test-provider", service.getActiveProviderName());
        }

        @Test
        @DisplayName("should return provider health status")
        void shouldReturnProviderHealthStatus() {
            when(provider.isAvailable()).thenReturn(true);

            assertTrue(service.isProviderHealthy());
        }
    }

    @Nested
    @DisplayName("invalidateCache")
    class InvalidateCache {

        @Test
        @DisplayName("should invalidate all cached entries")
        void shouldInvalidateCache() {
            // Populate cache
            var claims = Map.<String, Object>of("jti", "cache-test");
            var result = new TranslatedClaims(Set.of("role"), Set.of(), Map.of());
            when(provider.translate(any(), any(), any()))
                    .thenReturn(Uni.createFrom().item(result));

            service.translate(ISSUER, SUBJECT, claims).await().atMost(Duration.ofSeconds(5));

            // Invalidate
            service.invalidateCache();

            verify(metrics).updateCacheSize(0);
        }
    }
}
