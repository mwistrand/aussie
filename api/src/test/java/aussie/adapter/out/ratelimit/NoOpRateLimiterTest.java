package aussie.adapter.out.ratelimit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.RateLimitKey;
import aussie.core.model.ratelimit.RateLimitKeyType;

@DisplayName("NoOpRateLimiter")
class NoOpRateLimiterTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final NoOpRateLimiter limiter = NoOpRateLimiter.getInstance();

    private RateLimitKey createKey() {
        return new RateLimitKey(RateLimitKeyType.HTTP, "client-1", "service-1", Optional.of("endpoint-1"));
    }

    private EffectiveRateLimit createLimit() {
        return new EffectiveRateLimit(100, 60, 10);
    }

    @Nested
    @DisplayName("Singleton")
    class Singleton {

        @Test
        @DisplayName("should return same instance on repeated calls")
        void sameInstance() {
            var instance1 = NoOpRateLimiter.getInstance();
            var instance2 = NoOpRateLimiter.getInstance();

            assertSame(instance1, instance2);
        }
    }

    @Nested
    @DisplayName("isEnabled")
    class IsEnabled {

        @Test
        @DisplayName("should return false")
        void returnsFalse() {
            assertFalse(limiter.isEnabled());
        }
    }

    @Nested
    @DisplayName("checkAndConsume")
    class CheckAndConsume {

        @Test
        @DisplayName("should return allowed decision")
        void returnsAllow() {
            var decision =
                    limiter.checkAndConsume(createKey(), createLimit()).await().atMost(TIMEOUT);

            assertNotNull(decision);
            assertTrue(decision.allowed());
        }
    }

    @Nested
    @DisplayName("getStatus")
    class GetStatus {

        @Test
        @DisplayName("should return allowed decision")
        void returnsAllow() {
            var decision = limiter.getStatus(createKey(), createLimit()).await().atMost(TIMEOUT);

            assertNotNull(decision);
            assertTrue(decision.allowed());
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset {

        @Test
        @DisplayName("should complete without error")
        void completesSuccessfully() {
            assertDoesNotThrow(() -> limiter.reset(createKey()).await().atMost(TIMEOUT));
        }
    }

    @Nested
    @DisplayName("removeKeysMatching")
    class RemoveKeysMatching {

        @Test
        @DisplayName("should complete without error")
        void completesSuccessfully() {
            assertDoesNotThrow(
                    () -> limiter.removeKeysMatching("some-pattern*").await().atMost(TIMEOUT));
        }
    }
}
