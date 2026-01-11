package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

            var result = service.translate(ISSUER, SUBJECT, claims).await().indefinitely();

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
            service.translate(ISSUER, SUBJECT, claims).await().indefinitely();
            // Second call - should hit cache
            var result = service.translate(ISSUER, SUBJECT, claims).await().indefinitely();

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
            service.translate(ISSUER, SUBJECT, claims1).await().indefinitely();
            service.translate(ISSUER, SUBJECT, claims2).await().indefinitely();

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
            service.translate(ISSUER, SUBJECT, claims).await().indefinitely();
            service.translate(ISSUER, SUBJECT, claims).await().indefinitely();

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

            var actual1 = service.translate(ISSUER, SUBJECT, claims1).await().indefinitely();
            var actual2 = service.translate(ISSUER, SUBJECT, claims2).await().indefinitely();

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
                service.translate(ISSUER, SUBJECT, claims).await().indefinitely();
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
}
