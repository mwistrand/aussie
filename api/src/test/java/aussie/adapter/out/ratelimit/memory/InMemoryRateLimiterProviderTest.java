package aussie.adapter.out.ratelimit.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

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
        @DisplayName("should create a limiter with the default constructor")
        void shouldCreateWithDefaultConstructor() {
            final var provider = new InMemoryRateLimiterProvider();

            final var limiter = provider.createRateLimiter();
            createdLimiter = (InMemoryRateLimiter) limiter;

            assertInstanceOf(InMemoryRateLimiter.class, limiter);
        }

        @Test
        @DisplayName("should create InMemoryRateLimiter with configured constructor")
        void shouldCreateWithConfiguredConstructor() {
            final var provider = new InMemoryRateLimiterProvider(true, 60);

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
            final var provider = InMemoryRateLimiterProvider.configured(true, 120);

            assertEquals(0, provider.priority());
            assertEquals("memory", provider.name());
            assertTrue(provider.isAvailable());

            final var limiter = provider.createRateLimiter();
            createdLimiter = (InMemoryRateLimiter) limiter;

            assertInstanceOf(InMemoryRateLimiter.class, limiter);
        }
    }
}
