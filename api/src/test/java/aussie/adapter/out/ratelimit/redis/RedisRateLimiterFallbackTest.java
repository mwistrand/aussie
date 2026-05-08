package aussie.adapter.out.ratelimit.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.RateLimitingConfig.RateLimitFallbackBehavior;
import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.RateLimitDecision;
import aussie.core.model.ratelimit.RateLimitKey;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.RateLimiter;

@DisplayName("RedisRateLimiter fallback behavior")
class RedisRateLimiterFallbackTest {

    private static final RateLimitKey KEY = RateLimitKey.http("client-1", "service-a", "endpoint-1");
    private static final EffectiveRateLimit LIMIT = new EffectiveRateLimit(60, 60L, 60L);

    private ReactiveRedisDataSource redisDataSource;
    private Metrics metrics;
    private RateLimiter localFallback;

    private RedisRateLimiter newLimiter(RateLimitFallbackBehavior behavior, RateLimiter fallback) {
        // Default answer: all execute(...) calls fail with a synthetic Redis error so the
        // limiter always hits the fallback path. Other methods get Mockito's default
        // behavior. This sidesteps Mockito's awkward varargs matching for execute(String,
        // String...) — the call we're stubbing here passes a different number of varargs
        // depending on which Lua script runs.
        redisDataSource = mock(ReactiveRedisDataSource.class, invocation -> {
            if ("execute".equals(invocation.getMethod().getName())) {
                return Uni.createFrom().failure(new RuntimeException("Redis unreachable"));
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });

        @SuppressWarnings("unchecked")
        var keyCommands = (ReactiveKeyCommands<String>) mock(ReactiveKeyCommands.class);
        when(redisDataSource.key(String.class)).thenReturn(keyCommands);

        metrics = mock(Metrics.class);
        return new RedisRateLimiter(redisDataSource, true, fallback, behavior, metrics);
    }

    @Nested
    @DisplayName("LOCAL_BUCKET")
    class LocalBucket {

        @Test
        @DisplayName("delegates to the in-memory fallback and tags the metric local-bucket")
        void delegatesToFallback() {
            localFallback = mock(RateLimiter.class);
            when(localFallback.checkAndConsume(KEY, LIMIT))
                    .thenReturn(Uni.createFrom().item(RateLimitDecision.allow()));

            final var limiter = newLimiter(RateLimitFallbackBehavior.LOCAL_BUCKET, localFallback);

            final var decision = limiter.checkAndConsume(KEY, LIMIT).await().atMost(Duration.ofSeconds(1));

            assertTrue(decision.allowed());
            verify(localFallback, times(1)).checkAndConsume(KEY, LIMIT);
            verify(metrics).recordRateLimitFallback("service-a", "local-bucket");
        }

        @Test
        @DisplayName("falls closed when LOCAL_BUCKET is configured but no fallback bucket is wired")
        void localBucketWithoutFallbackFailsClosed() {
            final var limiter = newLimiter(RateLimitFallbackBehavior.LOCAL_BUCKET, null);

            final var decision = limiter.checkAndConsume(KEY, LIMIT).await().atMost(Duration.ofSeconds(1));

            assertFalse(decision.allowed(), "missing fallback must fail closed, not open");
            verify(metrics).recordRateLimitFallback("service-a", "deny");
        }
    }

    @Nested
    @DisplayName("DENY")
    class Deny {

        @Test
        @DisplayName("rejects the request and emits a 429-style decision")
        void deniesRequest() {
            final var limiter = newLimiter(RateLimitFallbackBehavior.DENY, mock(RateLimiter.class));

            final var decision = limiter.checkAndConsume(KEY, LIMIT).await().atMost(Duration.ofSeconds(1));

            assertFalse(decision.allowed());
            assertEquals(LIMIT.windowSeconds(), decision.retryAfterSeconds());
            verify(metrics).recordRateLimitFallback("service-a", "deny");
        }

        @Test
        @DisplayName("does not consult the fallback limiter when DENY is configured")
        void doesNotConsultFallback() {
            final var fallback = mock(RateLimiter.class);
            final var limiter = newLimiter(RateLimitFallbackBehavior.DENY, fallback);

            limiter.checkAndConsume(KEY, LIMIT).await().atMost(Duration.ofSeconds(1));

            verify(fallback, never())
                    .checkAndConsume(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }
    }

    @Nested
    @DisplayName("ALLOW (legacy)")
    class Allow {

        @Test
        @DisplayName("preserves fail-open behavior and tags the metric allow")
        void allowsRequest() {
            final var limiter = newLimiter(RateLimitFallbackBehavior.ALLOW, mock(RateLimiter.class));

            final var decision = limiter.checkAndConsume(KEY, LIMIT).await().atMost(Duration.ofSeconds(1));

            assertTrue(decision.allowed());
            verify(metrics).recordRateLimitFallback("service-a", "allow");
        }
    }

    @Nested
    @DisplayName("getStatus()")
    class StatusPath {

        @Test
        @DisplayName("LOCAL_BUCKET routes status checks through the fallback")
        void statusGoesThroughFallback() {
            localFallback = mock(RateLimiter.class);
            when(localFallback.getStatus(KEY, LIMIT))
                    .thenReturn(Uni.createFrom().item(RateLimitDecision.allow()));

            final var limiter = newLimiter(RateLimitFallbackBehavior.LOCAL_BUCKET, localFallback);

            limiter.getStatus(KEY, LIMIT).await().atMost(Duration.ofSeconds(1));

            verify(localFallback).getStatus(KEY, LIMIT);
            verify(localFallback, never()).checkAndConsume(KEY, LIMIT);
        }
    }
}
