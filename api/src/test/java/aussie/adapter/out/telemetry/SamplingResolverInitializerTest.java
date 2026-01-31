package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.SamplingConfig;

@DisplayName("SamplingResolverInitializer")
class SamplingResolverInitializerTest {

    private SamplingResolver resolver;
    private SamplingConfig config;
    private SamplingConfig.LookupConfig lookupConfig;
    private SamplingConfig.CacheConfig cacheConfig;
    private SamplingResolverInitializer initializer;

    @BeforeEach
    void setUp() {
        resolver = mock(SamplingResolver.class);
        config = mock(SamplingConfig.class);
        lookupConfig = mock(SamplingConfig.LookupConfig.class);
        cacheConfig = mock(SamplingConfig.CacheConfig.class);

        when(config.lookup()).thenReturn(lookupConfig);
        when(config.cache()).thenReturn(cacheConfig);
        when(lookupConfig.timeout()).thenReturn(Duration.ofSeconds(5));
        when(cacheConfig.redisEnabled()).thenReturn(false);
        when(cacheConfig.redisTtl()).thenReturn(Duration.ofMinutes(5));

        // Valid defaults
        when(config.enabled()).thenReturn(true);
        when(config.defaultRate()).thenReturn(0.5);
        when(config.minimumRate()).thenReturn(0.0);
        when(config.maximumRate()).thenReturn(1.0);

        initializer = new SamplingResolverInitializer(resolver, config);
    }

    @Nested
    @DisplayName("Configuration validation")
    class ConfigurationValidation {

        @Test
        @DisplayName("should pass with valid configuration")
        void shouldPassWithValidConfiguration() {
            assertDoesNotThrow(() -> initializer.onStart(new StartupEvent()));
        }

        @Test
        @DisplayName("should throw when minimumRate is below 0.0")
        void shouldThrowWhenMinimumRateBelowZero() {
            when(config.minimumRate()).thenReturn(-0.1);
            initializer = new SamplingResolverInitializer(resolver, config);

            var exception = assertThrows(IllegalStateException.class, () -> initializer.onStart(new StartupEvent()));

            assertTrue(exception.getMessage().contains("minimumRate must be between 0.0 and 1.0"));
        }

        @Test
        @DisplayName("should throw when minimumRate is above 1.0")
        void shouldThrowWhenMinimumRateAboveOne() {
            when(config.minimumRate()).thenReturn(1.5);
            initializer = new SamplingResolverInitializer(resolver, config);

            var exception = assertThrows(IllegalStateException.class, () -> initializer.onStart(new StartupEvent()));

            assertTrue(exception.getMessage().contains("minimumRate must be between 0.0 and 1.0"));
        }

        @Test
        @DisplayName("should throw when maximumRate is below 0.0")
        void shouldThrowWhenMaximumRateBelowZero() {
            when(config.maximumRate()).thenReturn(-0.1);
            initializer = new SamplingResolverInitializer(resolver, config);

            var exception = assertThrows(IllegalStateException.class, () -> initializer.onStart(new StartupEvent()));

            assertTrue(exception.getMessage().contains("maximumRate must be between 0.0 and 1.0"));
        }

        @Test
        @DisplayName("should throw when maximumRate is above 1.0")
        void shouldThrowWhenMaximumRateAboveOne() {
            when(config.maximumRate()).thenReturn(1.5);
            initializer = new SamplingResolverInitializer(resolver, config);

            var exception = assertThrows(IllegalStateException.class, () -> initializer.onStart(new StartupEvent()));

            assertTrue(exception.getMessage().contains("maximumRate must be between 0.0 and 1.0"));
        }

        @Test
        @DisplayName("should throw when defaultRate is below 0.0")
        void shouldThrowWhenDefaultRateBelowZero() {
            when(config.defaultRate()).thenReturn(-0.1);
            initializer = new SamplingResolverInitializer(resolver, config);

            var exception = assertThrows(IllegalStateException.class, () -> initializer.onStart(new StartupEvent()));

            assertTrue(exception.getMessage().contains("defaultRate must be between 0.0 and 1.0"));
        }

        @Test
        @DisplayName("should throw when defaultRate is above 1.0")
        void shouldThrowWhenDefaultRateAboveOne() {
            when(config.defaultRate()).thenReturn(1.5);
            initializer = new SamplingResolverInitializer(resolver, config);

            var exception = assertThrows(IllegalStateException.class, () -> initializer.onStart(new StartupEvent()));

            assertTrue(exception.getMessage().contains("defaultRate must be between 0.0 and 1.0"));
        }

        @Test
        @DisplayName("should throw when minimumRate exceeds maximumRate")
        void shouldThrowWhenMinExceedsMax() {
            when(config.minimumRate()).thenReturn(0.8);
            when(config.maximumRate()).thenReturn(0.5);
            initializer = new SamplingResolverInitializer(resolver, config);

            var exception = assertThrows(IllegalStateException.class, () -> initializer.onStart(new StartupEvent()));

            assertTrue(exception.getMessage().contains("minimumRate (0.8) must be <= maximumRate (0.5)"));
        }

        @Test
        @DisplayName("should aggregate multiple errors into single exception")
        void shouldAggregateMultipleErrors() {
            when(config.minimumRate()).thenReturn(-0.1);
            when(config.maximumRate()).thenReturn(1.5);
            when(config.defaultRate()).thenReturn(-0.5);
            initializer = new SamplingResolverInitializer(resolver, config);

            var exception = assertThrows(IllegalStateException.class, () -> initializer.onStart(new StartupEvent()));

            var message = exception.getMessage();
            assertTrue(message.contains("minimumRate must be between 0.0 and 1.0"));
            assertTrue(message.contains("maximumRate must be between 0.0 and 1.0"));
            assertTrue(message.contains("defaultRate must be between 0.0 and 1.0"));
        }

        @Test
        @DisplayName("should warn but not throw when defaultRate is outside bounds")
        void shouldWarnWhenDefaultOutsideBounds() {
            when(config.defaultRate()).thenReturn(0.8);
            when(config.minimumRate()).thenReturn(0.1);
            when(config.maximumRate()).thenReturn(0.5);
            initializer = new SamplingResolverInitializer(resolver, config);

            // Should not throw - only warns
            assertDoesNotThrow(() -> initializer.onStart(new StartupEvent()));
        }

        @Test
        @DisplayName("should skip validation when sampling is disabled")
        void shouldSkipValidationWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            when(config.minimumRate()).thenReturn(-0.1); // Invalid but should be ignored
            initializer = new SamplingResolverInitializer(resolver, config);

            assertDoesNotThrow(() -> initializer.onStart(new StartupEvent()));
        }

        @Test
        @DisplayName("should accept boundary values")
        void shouldAcceptBoundaryValues() {
            when(config.minimumRate()).thenReturn(0.0);
            when(config.maximumRate()).thenReturn(1.0);
            when(config.defaultRate()).thenReturn(0.0);
            initializer = new SamplingResolverInitializer(resolver, config);

            assertDoesNotThrow(() -> initializer.onStart(new StartupEvent()));
        }

        @Test
        @DisplayName("should accept equal minimum and maximum")
        void shouldAcceptEqualMinMax() {
            when(config.minimumRate()).thenReturn(0.5);
            when(config.maximumRate()).thenReturn(0.5);
            when(config.defaultRate()).thenReturn(0.5);
            initializer = new SamplingResolverInitializer(resolver, config);

            assertDoesNotThrow(() -> initializer.onStart(new StartupEvent()));
        }
    }

    @Nested
    @DisplayName("Holder lifecycle")
    class HolderLifecycle {

        @Test
        @DisplayName("should set resolver in holder on startup")
        void shouldSetResolverOnStartup() {
            initializer.onStart(new StartupEvent());

            assertEquals(resolver, SamplingResolverHolder.get().orElse(null));
        }

        @Test
        @DisplayName("should clear resolver from holder on shutdown")
        void shouldClearResolverOnShutdown() {
            initializer.onStart(new StartupEvent());
            initializer.onStop(new io.quarkus.runtime.ShutdownEvent());

            assertTrue(SamplingResolverHolder.get().isEmpty());
        }
    }
}
