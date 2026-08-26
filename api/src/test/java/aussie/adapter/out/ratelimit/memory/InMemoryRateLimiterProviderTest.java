package aussie.adapter.out.ratelimit.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.model.ratelimit.RateLimitAlgorithm;
import aussie.core.service.ratelimit.AlgorithmRegistry;

@DisplayName("InMemoryRateLimiterProvider")
@ExtendWith(MockitoExtension.class)
class InMemoryRateLimiterProviderTest {

    private InMemoryRateLimiter createdLimiter;

    @AfterEach
    void tearDown() {
        if (createdLimiter != null) {
            createdLimiter.shutdown();
        }
    }

    @Nested
    @DisplayName("priority()")
    class PriorityTests {

        @Test
        @DisplayName("should return 0")
        void shouldReturnZero() {
            final var provider = new InMemoryRateLimiterProvider();

            assertEquals(0, provider.priority());
        }
    }

    @Nested
    @DisplayName("name()")
    class NameTests {

        @Test
        @DisplayName("should return 'memory'")
        void shouldReturnMemory() {
            final var provider = new InMemoryRateLimiterProvider();

            assertEquals("memory", provider.name());
        }
    }

    @Nested
    @DisplayName("isAvailable()")
    class IsAvailableTests {

        @Test
        @DisplayName("should always return true")
        void shouldAlwaysReturnTrue() {
            final var provider = new InMemoryRateLimiterProvider();

            assertTrue(provider.isAvailable());
        }
    }

    @Nested
    @DisplayName("createRateLimiter()")
    class CreateRateLimiterTests {

        @Test
        @DisplayName("should throw IllegalStateException with default constructor")
        void shouldThrowWithDefaultConstructor() {
            final var provider = new InMemoryRateLimiterProvider();

            final var exception = assertThrows(IllegalStateException.class, provider::createRateLimiter);

            assertTrue(exception.getMessage().contains("not configured"));
        }

        @Test
        @DisplayName("should create InMemoryRateLimiter with configured constructor")
        void shouldCreateWithConfiguredConstructor() {
            final var registry = mock(AlgorithmRegistry.class);
            final var provider = new InMemoryRateLimiterProvider(registry, RateLimitAlgorithm.BUCKET, true, 60);

            final var limiter = provider.createRateLimiter();
            createdLimiter = (InMemoryRateLimiter) limiter;

            assertInstanceOf(InMemoryRateLimiter.class, limiter);
        }
    }

    @Nested
    @DisplayName("configured()")
    class ConfiguredFactoryTests {

        @Test
        @DisplayName("should create a configured provider via factory method")
        void shouldCreateConfiguredProvider() {
            final var registry = mock(AlgorithmRegistry.class);
            final var provider = InMemoryRateLimiterProvider.configured(registry, RateLimitAlgorithm.BUCKET, true, 120);

            assertEquals(0, provider.priority());
            assertEquals("memory", provider.name());
            assertTrue(provider.isAvailable());

            final var limiter = provider.createRateLimiter();
            createdLimiter = (InMemoryRateLimiter) limiter;

            assertInstanceOf(InMemoryRateLimiter.class, limiter);
        }
    }
}
