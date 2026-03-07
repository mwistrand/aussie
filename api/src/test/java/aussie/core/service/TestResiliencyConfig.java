package aussie.core.service;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import aussie.core.config.ResiliencyConfig;

/**
 * Test helper for creating {@link ResiliencyConfig} instances with specific settings.
 */
final class TestResiliencyConfig {

    private TestResiliencyConfig() {}

    /**
     * Create a permissive resiliency config that does not constrain service timeouts.
     */
    static ResiliencyConfig permissive() {
        return withMaxRequestTimeout(Duration.ofDays(1));
    }

    /**
     * Create a resiliency config with a specific maximum request timeout for validation.
     */
    static ResiliencyConfig withMaxRequestTimeout(Duration maxRequestTimeout) {
        final var httpConfig = mock(ResiliencyConfig.HttpConfig.class);
        lenient().when(httpConfig.requestTimeout()).thenReturn(Duration.ofSeconds(30));
        lenient().when(httpConfig.maxRequestTimeout()).thenReturn(maxRequestTimeout);

        final var config = mock(ResiliencyConfig.class);
        lenient().when(config.http()).thenReturn(httpConfig);
        return config;
    }
}
