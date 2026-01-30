package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.SamplingConfig;

@DisplayName("HierarchicalSamplerProvider")
class HierarchicalSamplerProviderTest {

    private SamplingConfig config;

    @BeforeEach
    void setUp() {
        config = mock(SamplingConfig.class);
        when(config.defaultRate()).thenReturn(1.0);
    }

    @Nested
    @DisplayName("sampler")
    class SamplerProduction {

        @Test
        @DisplayName("should return HierarchicalSampler when enabled")
        void shouldReturnHierarchicalSamplerWhenEnabled() {
            when(config.enabled()).thenReturn(true);
            var provider = new HierarchicalSamplerProvider(config);

            var sampler = provider.sampler();

            assertNotNull(sampler);
            // The description should contain "HierarchicalSampler" indicating it's wrapped
            assertTrue(sampler.getDescription().contains("HierarchicalSampler"));
        }

        @Test
        @DisplayName("should return standard sampler when disabled")
        void shouldReturnStandardSamplerWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            var provider = new HierarchicalSamplerProvider(config);

            var sampler = provider.sampler();

            assertNotNull(sampler);
            // Should not contain HierarchicalSampler
            assertFalse(sampler.getDescription().contains("HierarchicalSampler"));
            // Should be a parent-based sampler with trace ID ratio
            assertTrue(sampler.getDescription().contains("ParentBased"));
        }

        @Test
        @DisplayName("should wrap sampler in parentBased when enabled")
        void shouldWrapInParentBasedWhenEnabled() {
            when(config.enabled()).thenReturn(true);
            var provider = new HierarchicalSamplerProvider(config);

            var sampler = provider.sampler();

            assertTrue(sampler.getDescription().contains("ParentBased"));
        }

        @Test
        @DisplayName("should wrap sampler in parentBased when disabled")
        void shouldWrapInParentBasedWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            var provider = new HierarchicalSamplerProvider(config);

            var sampler = provider.sampler();

            assertTrue(sampler.getDescription().contains("ParentBased"));
        }

        @Test
        @DisplayName("should use configured default rate")
        void shouldUseConfiguredDefaultRate() {
            when(config.enabled()).thenReturn(false);
            when(config.defaultRate()).thenReturn(0.5);
            var provider = new HierarchicalSamplerProvider(config);

            var sampler = provider.sampler();

            // The description should include the ratio
            assertTrue(sampler.getDescription().contains("0.5"));
        }
    }
}
