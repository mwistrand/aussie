package aussie.adapter.out.storage.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.out.storage.redis.RedisTimeoutHelper.RedisTimeoutException;
import aussie.core.port.out.Metrics;

@DisplayName("RedisTimeoutHelper")
@ExtendWith(MockitoExtension.class)
class RedisTimeoutHelperTest {

    private static final Duration TIMEOUT = Duration.ofMillis(50);
    private static final String REPOSITORY_NAME = "TestRepository";
    private static final String OPERATION_NAME = "testOperation";

    @Mock
    private Metrics metrics;

    private RedisTimeoutHelper helper;

    @BeforeEach
    void setUp() {
        helper = new RedisTimeoutHelper(TIMEOUT, metrics, REPOSITORY_NAME);
    }

    @Nested
    @DisplayName("withTimeout()")
    class WithTimeoutTests {

        @Test
        @DisplayName("should return result when operation completes within timeout")
        void shouldReturnResultWhenOperationCompletesWithinTimeout() {
            final var expected = "success";
            final var operation = Uni.createFrom().item(expected);

            final var result =
                    helper.withTimeout(operation, OPERATION_NAME).await().indefinitely();

            assertEquals(expected, result);
            verifyNoInteractions(metrics);
        }

        @Test
        @DisplayName("should throw RedisTimeoutException when operation times out")
        void shouldThrowRedisTimeoutExceptionWhenOperationTimesOut() {
            final var operation = Uni.createFrom().<String>nothing();

            final var exception = assertThrows(
                    RedisTimeoutException.class,
                    () -> helper.withTimeout(operation, OPERATION_NAME).await().indefinitely());

            assertEquals(OPERATION_NAME, exception.getOperation());
            assertEquals(REPOSITORY_NAME, exception.getRepository());
            assertTrue(exception.getMessage().contains(OPERATION_NAME));
            assertTrue(exception.getMessage().contains(REPOSITORY_NAME));
            verify(metrics).recordRedisTimeout(eq(REPOSITORY_NAME), eq(OPERATION_NAME));
        }
    }

    @Nested
    @DisplayName("withTimeoutGraceful()")
    class WithTimeoutGracefulTests {

        @Test
        @DisplayName("should return wrapped result when operation completes within timeout")
        void shouldReturnWrappedResultWhenOperationCompletesWithinTimeout() {
            final var expected = "success";
            final var operation = Uni.createFrom().item(expected);

            final var result = helper.withTimeoutGraceful(operation, OPERATION_NAME)
                    .await()
                    .indefinitely();

            assertTrue(result.isPresent());
            assertEquals(expected, result.get());
            verifyNoInteractions(metrics);
        }

        @Test
        @DisplayName("should return empty Optional when result is null")
        void shouldReturnEmptyOptionalWhenResultIsNull() {
            final Uni<String> operation = Uni.createFrom().nullItem();

            final var result = helper.withTimeoutGraceful(operation, OPERATION_NAME)
                    .await()
                    .indefinitely();

            assertTrue(result.isEmpty());
            verifyNoInteractions(metrics);
        }

        @Test
        @DisplayName("should return empty Optional when operation times out")
        void shouldReturnEmptyOptionalWhenOperationTimesOut() {
            final var operation = Uni.createFrom().<String>nothing();

            final var result = helper.withTimeoutGraceful(operation, OPERATION_NAME)
                    .await()
                    .indefinitely();

            assertTrue(result.isEmpty());
            verify(metrics).recordRedisTimeout(eq(REPOSITORY_NAME), eq(OPERATION_NAME));
        }

        @Test
        @DisplayName("should return empty Optional when operation fails with exception")
        void shouldReturnEmptyOptionalWhenOperationFailsWithException() {
            final var operation = Uni.createFrom().<String>failure(new RuntimeException("Connection refused"));

            final var result = helper.withTimeoutGraceful(operation, OPERATION_NAME)
                    .await()
                    .indefinitely();

            assertTrue(result.isEmpty());
            verify(metrics).recordRedisFailure(eq(REPOSITORY_NAME), eq(OPERATION_NAME));
        }
    }

    @Nested
    @DisplayName("withTimeoutFallback()")
    class WithTimeoutFallbackTests {

        @Test
        @DisplayName("should return result when operation completes within timeout")
        void shouldReturnResultWhenOperationCompletesWithinTimeout() {
            final var expected = 42L;
            final var operation = Uni.createFrom().item(expected);

            final var result = helper.withTimeoutFallback(operation, OPERATION_NAME, () -> 0L)
                    .await()
                    .indefinitely();

            assertEquals(expected, result);
            verifyNoInteractions(metrics);
        }

        @Test
        @DisplayName("should return fallback value when operation times out")
        void shouldReturnFallbackValueWhenOperationTimesOut() {
            final var fallbackValue = 0L;
            final var operation = Uni.createFrom().<Long>nothing();

            final var result = helper.withTimeoutFallback(operation, OPERATION_NAME, () -> fallbackValue)
                    .await()
                    .indefinitely();

            assertEquals(fallbackValue, result);
            verify(metrics).recordRedisTimeout(eq(REPOSITORY_NAME), eq(OPERATION_NAME));
        }

        @Test
        @DisplayName("should return null fallback when configured")
        void shouldReturnNullFallbackWhenConfigured() {
            final var operation = Uni.createFrom().<String>nothing();

            final var result = helper.withTimeoutFallback(operation, OPERATION_NAME, () -> null)
                    .await()
                    .indefinitely();

            assertEquals(null, result);
            verify(metrics).recordRedisTimeout(eq(REPOSITORY_NAME), eq(OPERATION_NAME));
        }

        @Test
        @DisplayName("should return fallback value when operation fails with exception")
        void shouldReturnFallbackValueWhenOperationFailsWithException() {
            final var fallbackValue = 0L;
            final var operation = Uni.createFrom().<Long>failure(new RuntimeException("Connection refused"));

            final var result = helper.withTimeoutFallback(operation, OPERATION_NAME, () -> fallbackValue)
                    .await()
                    .indefinitely();

            assertEquals(fallbackValue, result);
            verify(metrics).recordRedisFailure(eq(REPOSITORY_NAME), eq(OPERATION_NAME));
        }
    }

    @Nested
    @DisplayName("withTimeoutSilent()")
    class WithTimeoutSilentTests {

        @Test
        @DisplayName("should complete normally when operation completes within timeout")
        void shouldCompleteNormallyWhenOperationCompletesWithinTimeout() {
            final var operation = Uni.createFrom().voidItem();

            final var result =
                    helper.withTimeoutSilent(operation, OPERATION_NAME).await().indefinitely();

            assertEquals(null, result);
            verifyNoInteractions(metrics);
        }

        @Test
        @DisplayName("should complete with null when operation times out")
        void shouldCompleteWithNullWhenOperationTimesOut() {
            final var operation = Uni.createFrom().<Void>nothing();

            final var result =
                    helper.withTimeoutSilent(operation, OPERATION_NAME).await().indefinitely();

            assertEquals(null, result);
            verify(metrics).recordRedisTimeout(eq(REPOSITORY_NAME), eq(OPERATION_NAME));
        }

        @Test
        @DisplayName("should complete with null when operation fails with exception")
        void shouldCompleteWithNullWhenOperationFailsWithException() {
            final var operation = Uni.createFrom().<Void>failure(new RuntimeException("Connection refused"));

            final var result =
                    helper.withTimeoutSilent(operation, OPERATION_NAME).await().indefinitely();

            assertEquals(null, result);
            verify(metrics).recordRedisFailure(eq(REPOSITORY_NAME), eq(OPERATION_NAME));
        }
    }

    @Nested
    @DisplayName("null metrics handling")
    class NullMetricsTests {

        @Test
        @DisplayName("should not throw when metrics is null and operation times out")
        void shouldNotThrowWhenMetricsIsNullAndOperationTimesOut() {
            final var helperWithoutMetrics = new RedisTimeoutHelper(TIMEOUT, null, REPOSITORY_NAME);
            final var operation = Uni.createFrom().<String>nothing();

            final var result = helperWithoutMetrics
                    .withTimeoutGraceful(operation, OPERATION_NAME)
                    .await()
                    .indefinitely();

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("RedisTimeoutException")
    class RedisTimeoutExceptionTests {

        @Test
        @DisplayName("should contain operation and repository information")
        void shouldContainOperationAndRepositoryInformation() {
            final var exception = new RedisTimeoutException(OPERATION_NAME, REPOSITORY_NAME);

            assertEquals(OPERATION_NAME, exception.getOperation());
            assertEquals(REPOSITORY_NAME, exception.getRepository());
            assertEquals(
                    "Redis operation timeout: " + OPERATION_NAME + " in " + REPOSITORY_NAME, exception.getMessage());
        }
    }
}
