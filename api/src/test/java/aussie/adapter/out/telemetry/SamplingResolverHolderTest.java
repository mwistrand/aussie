package aussie.adapter.out.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SamplingResolverHolder")
class SamplingResolverHolderTest {

    @BeforeEach
    @AfterEach
    void clearHolder() {
        SamplingResolverHolder.clear();
    }

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        @DisplayName("should return empty when not set")
        void shouldReturnEmptyWhenNotSet() {
            var result = SamplingResolverHolder.get();

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("should return resolver after set")
        void shouldReturnResolverAfterSet() {
            var mockResolver = mock(SamplingResolver.class);

            SamplingResolverHolder.set(mockResolver);
            var result = SamplingResolverHolder.get();

            assertTrue(result.isPresent());
            assertEquals(mockResolver, result.get());
        }
    }

    @Nested
    @DisplayName("set")
    class Set {

        @Test
        @DisplayName("should make resolver available via get")
        void shouldMakeResolverAvailableViaGet() {
            var mockResolver = mock(SamplingResolver.class);

            SamplingResolverHolder.set(mockResolver);

            assertTrue(SamplingResolverHolder.get().isPresent());
        }

        @Test
        @DisplayName("should allow overwriting existing resolver")
        void shouldAllowOverwritingExistingResolver() {
            var resolver1 = mock(SamplingResolver.class);
            var resolver2 = mock(SamplingResolver.class);

            SamplingResolverHolder.set(resolver1);
            SamplingResolverHolder.set(resolver2);

            assertEquals(resolver2, SamplingResolverHolder.get().get());
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        @DisplayName("should remove resolver")
        void shouldRemoveResolver() {
            var mockResolver = mock(SamplingResolver.class);
            SamplingResolverHolder.set(mockResolver);

            SamplingResolverHolder.clear();

            assertFalse(SamplingResolverHolder.get().isPresent());
        }

        @Test
        @DisplayName("should be safe to call when already empty")
        void shouldBeSafeToCallWhenAlreadyEmpty() {
            // Should not throw
            SamplingResolverHolder.clear();

            assertFalse(SamplingResolverHolder.get().isPresent());
        }
    }
}
