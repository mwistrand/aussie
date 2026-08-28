package aussie.adapter.out.ratelimit.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import aussie.adapter.out.ratelimit.memory.InMemoryRateLimiter;
import aussie.core.config.RateLimitingConfig.RateLimitFallbackBehavior;
import aussie.core.model.ratelimit.EffectiveRateLimit;
import aussie.core.model.ratelimit.RateLimitAlgorithm;
import aussie.core.model.ratelimit.RateLimitDecision;
import aussie.core.model.ratelimit.RateLimitKey;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.RateLimiter;
import aussie.core.service.ratelimit.AlgorithmRegistry;

@DisplayName("RedisRateLimiter fallback behavior")
class RedisRateLimiterFallbackTest {

    private static final RateLimitKey KEY = RateLimitKey.http("client-1", "service-a", "endpoint-1");
    private static final EffectiveRateLimit LIMIT = new EffectiveRateLimit(60, 60L, 60L);
    private static final EffectiveRateLimit EMERGENCY_LIMIT = new EffectiveRateLimit(1, 1, 1);

    private ReactiveRedisDataSource redisDataSource;
    private Metrics metrics;
    private RateLimiter localFallback;

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = RateLimitAlgorithm.class,
            names = {"FIXED_WINDOW", "SLIDING_WINDOW"})
    @DisplayName("rejects algorithms without Redis semantics")
    void rejectsUnsupportedAlgorithms(RateLimitAlgorithm algorithm) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RedisRateLimiter(
                        mock(ReactiveRedisDataSource.class),
                        true,
                        algorithm,
                        null,
                        RateLimitFallbackBehavior.DENY,
                        null));
    }

    @Test
    @DisplayName("closes the fallback when limiter construction fails")
    void closesFallbackWhenConstructionFails() {
        final var failure = new IllegalStateException("Redis unavailable");
        final var closeFailure = new IllegalStateException("Fallback close failed");
        final var dataSource = mock(ReactiveRedisDataSource.class);
        final var fallback = mock(RateLimiter.class);
        when(dataSource.key(String.class)).thenThrow(failure);
        doThrow(closeFailure).when(fallback).close();
        final var provider = RedisRateLimiterProvider.configured(
                dataSource,
                true,
                true,
                RateLimitAlgorithm.BUCKET,
                fallback,
                RateLimitFallbackBehavior.LOCAL_BUCKET,
                null);

        final var thrown = assertThrows(IllegalStateException.class, provider::createRateLimiter);

        assertSame(failure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeFailure, thrown.getSuppressed()[0]);
        verify(fallback).close();
    }

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
            when(localFallback.checkAndConsume(KEY, EMERGENCY_LIMIT))
                    .thenReturn(Uni.createFrom().item(RateLimitDecision.allow()));

            final var limiter = newLimiter(RateLimitFallbackBehavior.LOCAL_BUCKET, localFallback);

            final var decision = limiter.checkAndConsume(KEY, LIMIT).await().atMost(Duration.ofSeconds(1));

            assertTrue(decision.allowed());
            verify(localFallback, times(1)).checkAndConsume(KEY, EMERGENCY_LIMIT);
            verify(metrics).recordRateLimitFallback("service-a", "local-bucket");
        }

        @Test
        @DisplayName("keeps outage protection to one immediate request")
        void doesNotReplayDistributedBurst() {
            localFallback = new InMemoryRateLimiter(new AlgorithmRegistry(), RateLimitAlgorithm.BUCKET, true, 1);
            try (final var limiter = newLimiter(RateLimitFallbackBehavior.LOCAL_BUCKET, localFallback)) {

                final var first = limiter.checkAndConsume(KEY, new EffectiveRateLimit(100, 60, 100))
                        .await()
                        .atMost(Duration.ofSeconds(1));
                final var second = limiter.checkAndConsume(KEY, new EffectiveRateLimit(100, 60, 100))
                        .await()
                        .atMost(Duration.ofSeconds(1));

                assertTrue(first.allowed());
                assertFalse(second.allowed());
            }
        }

        @Test
        @DisplayName("preserves a zero rate or burst quota while using the emergency window")
        void preservesZeroQuota() {
            final var zeroEmergencyLimit = new EffectiveRateLimit(0, 1, 0);
            localFallback = mock(RateLimiter.class);
            when(localFallback.checkAndConsume(KEY, zeroEmergencyLimit))
                    .thenReturn(Uni.createFrom()
                            .item(RateLimitDecision.rejected(0, 1, Instant.now().plusSeconds(1), 1, 0, null)));

            final var limiter = newLimiter(RateLimitFallbackBehavior.LOCAL_BUCKET, localFallback);

            for (final var zeroLimit : List.of(new EffectiveRateLimit(0, 60, 60), new EffectiveRateLimit(60, 60, 0))) {
                final var decision =
                        limiter.checkAndConsume(KEY, zeroLimit).await().atMost(Duration.ofSeconds(1));
                assertFalse(decision.allowed());
            }
            verify(localFallback, times(2)).checkAndConsume(KEY, zeroEmergencyLimit);
        }

        @Test
        @DisplayName("closes the local fallback with the Redis limiter")
        void closesFallback() {
            localFallback = mock(RateLimiter.class);
            final var limiter = newLimiter(RateLimitFallbackBehavior.LOCAL_BUCKET, localFallback);

            limiter.close();
            limiter.close();

            verify(localFallback, times(1)).close();
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
        @DisplayName("is also the safe default when no behavior is supplied")
        void nullBehaviorDeniesRequest() {
            final var limiter = newLimiter(null, null);

            final var decision = limiter.checkAndConsume(KEY, LIMIT).await().atMost(Duration.ofSeconds(1));

            assertFalse(decision.allowed());
            verify(metrics).recordRateLimitFallback("service-a", "deny");
        }

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
            when(localFallback.getStatus(KEY, EMERGENCY_LIMIT))
                    .thenReturn(Uni.createFrom().item(RateLimitDecision.allow()));

            final var limiter = newLimiter(RateLimitFallbackBehavior.LOCAL_BUCKET, localFallback);

            limiter.getStatus(KEY, LIMIT).await().atMost(Duration.ofSeconds(1));

            verify(localFallback).getStatus(KEY, EMERGENCY_LIMIT);
            verify(localFallback, never()).checkAndConsume(KEY, LIMIT);
        }
    }
}
