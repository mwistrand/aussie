package aussie.core.service;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import aussie.core.config.RateLimitingConfig;

/**
 * Test helper for creating {@link RateLimitingConfig} instances with specific platform maximums.
 */
final class TestRateLimitingConfig {

    private TestRateLimitingConfig() {}

    /**
     * Create a permissive rate limiting config with no effective limits.
     */
    static RateLimitingConfig permissive() {
        final var config = mock(RateLimitingConfig.class);
        lenient().when(config.platformMaxWindowSeconds()).thenReturn(Long.MAX_VALUE);
        lenient().when(config.platformMaxRequestsPerWindow()).thenReturn(Long.MAX_VALUE);
        return config;
    }

    /**
     * Create a rate limiting config with a specific platform max window seconds.
     */
    static RateLimitingConfig withMaxWindowSeconds(long maxWindowSeconds) {
        final var config = mock(RateLimitingConfig.class);
        lenient().when(config.platformMaxWindowSeconds()).thenReturn(maxWindowSeconds);
        lenient().when(config.platformMaxRequestsPerWindow()).thenReturn(Long.MAX_VALUE);
        return config;
    }

    /**
     * Create a rate limiting config with a specific platform max requests per window.
     */
    static RateLimitingConfig withMaxRequestsPerWindow(long maxRequestsPerWindow) {
        final var config = mock(RateLimitingConfig.class);
        lenient().when(config.platformMaxRequestsPerWindow()).thenReturn(maxRequestsPerWindow);
        lenient().when(config.platformMaxWindowSeconds()).thenReturn(Long.MAX_VALUE);
        return config;
    }

    /**
     * Create a rate limiting config with specific platform maximums for both values.
     */
    static RateLimitingConfig withMaximums(long maxRequestsPerWindow, long maxWindowSeconds) {
        final var config = mock(RateLimitingConfig.class);
        lenient().when(config.platformMaxRequestsPerWindow()).thenReturn(maxRequestsPerWindow);
        lenient().when(config.platformMaxWindowSeconds()).thenReturn(maxWindowSeconds);
        return config;
    }
}
