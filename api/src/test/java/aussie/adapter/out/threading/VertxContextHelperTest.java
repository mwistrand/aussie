package aussie.adapter.out.threading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("VertxContextHelper")
class VertxContextHelperTest {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        if (vertx != null) {
            vertx.close();
        }
    }

    @Nested
    @DisplayName("isOnEventLoop")
    class IsOnEventLoop {

        @Test
        @DisplayName("should return false when not on Vert.x thread")
        void shouldReturnFalseWhenNotOnVertxThread() {
            // Running on JUnit thread, not Vert.x event loop
            assertFalse(VertxContextHelper.isOnEventLoop());
        }

        @Test
        @DisplayName("should return true when on Vert.x event loop")
        void shouldReturnTrueWhenOnEventLoop() throws InterruptedException {
            var result = new AtomicBoolean(false);
            var latch = new CountDownLatch(1);

            vertx.runOnContext(v -> {
                result.set(VertxContextHelper.isOnEventLoop());
                latch.countDown();
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(result.get());
        }
    }

    @Nested
    @DisplayName("eventLoopExecutor")
    class EventLoopExecutor {

        @Test
        @DisplayName("should return worker pool executor when no Vert.x context")
        void shouldReturnWorkerPoolWhenNoContext() {
            // Not on Vert.x thread - should fall back to worker pool
            var executor = VertxContextHelper.eventLoopExecutor();
            assertNotNull(executor);

            var executed = new AtomicBoolean(false);
            var latch = new CountDownLatch(1);

            executor.execute(() -> {
                executed.set(true);
                latch.countDown();
            });

            try {
                assertTrue(latch.await(5, TimeUnit.SECONDS));
                assertTrue(executed.get());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("should return event loop executor when on Vert.x context")
        void shouldReturnEventLoopExecutorWhenOnContext() throws InterruptedException {
            var executorRef = new AtomicReference<java.util.concurrent.Executor>();
            var executedOnEventLoop = new AtomicBoolean(false);
            var latch = new CountDownLatch(1);

            vertx.runOnContext(v -> {
                var executor = VertxContextHelper.eventLoopExecutor();
                executorRef.set(executor);

                // Execute on the returned executor and verify it runs on event loop
                executor.execute(() -> {
                    executedOnEventLoop.set(VertxContextHelper.isOnEventLoop());
                    latch.countDown();
                });
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertNotNull(executorRef.get());
            assertTrue(executedOnEventLoop.get());
        }
    }

    @Nested
    @DisplayName("captureContext")
    class CaptureContext {

        @Test
        @DisplayName("should throw when no Vert.x context available")
        void shouldThrowWhenNoContext() {
            var exception = assertThrows(IllegalStateException.class, VertxContextHelper::captureContext);

            assertTrue(exception.getMessage().contains("No Vert.x context"));
        }

        @Test
        @DisplayName("should capture context for later use")
        void shouldCaptureContextForLaterUse() throws InterruptedException {
            var executorRef = new AtomicReference<java.util.concurrent.Executor>();
            var capturedOnEventLoop = new AtomicBoolean(false);
            var executedOnEventLoop = new AtomicBoolean(false);
            var captureLatch = new CountDownLatch(1);
            var executeLatch = new CountDownLatch(1);

            // Capture context on event loop
            vertx.runOnContext(v -> {
                capturedOnEventLoop.set(VertxContextHelper.isOnEventLoop());
                executorRef.set(VertxContextHelper.captureContext());
                captureLatch.countDown();
            });

            assertTrue(captureLatch.await(5, TimeUnit.SECONDS));
            assertTrue(capturedOnEventLoop.get());
            assertNotNull(executorRef.get());

            // Execute later (from different thread) - should still run on event loop
            new Thread(() -> {
                        executorRef.get().execute(() -> {
                            executedOnEventLoop.set(VertxContextHelper.isOnEventLoop());
                            executeLatch.countDown();
                        });
                    })
                    .start();

            assertTrue(executeLatch.await(5, TimeUnit.SECONDS));
            assertTrue(executedOnEventLoop.get());
        }
    }

    @Nested
    @DisplayName("emitOnEventLoop")
    class EmitOnEventLoop {

        @Test
        @DisplayName("should emit Uni result on event loop when context available")
        void shouldEmitOnEventLoopWhenContextAvailable() throws InterruptedException {
            var emittedOnEventLoop = new AtomicBoolean(false);
            var resultRef = new AtomicReference<String>();
            var latch = new CountDownLatch(1);

            vertx.runOnContext(v -> {
                var uni = Uni.createFrom().item("test-value");
                VertxContextHelper.emitOnEventLoop(uni).subscribe().with(result -> {
                    resultRef.set(result);
                    emittedOnEventLoop.set(VertxContextHelper.isOnEventLoop());
                    latch.countDown();
                });
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals("test-value", resultRef.get());
            assertTrue(emittedOnEventLoop.get());
        }
    }

    @Nested
    @DisplayName("checkOnEventLoop")
    class CheckOnEventLoop {

        @Test
        @DisplayName("should return false and not throw when not on event loop")
        void shouldReturnFalseWhenNotOnEventLoop() {
            // Should not throw, just return false
            var result = VertxContextHelper.checkOnEventLoop();
            assertFalse(result);
        }

        @Test
        @DisplayName("should return true when on event loop")
        void shouldReturnTrueWhenOnEventLoop() throws InterruptedException {
            var result = new AtomicBoolean(false);
            var latch = new CountDownLatch(1);

            vertx.runOnContext(v -> {
                result.set(VertxContextHelper.checkOnEventLoop());
                latch.countDown();
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertTrue(result.get());
        }
    }

    @Nested
    @DisplayName("assertOnEventLoop")
    class AssertOnEventLoop {

        @Test
        @DisplayName("should not throw when on event loop")
        void shouldNotThrowWhenOnEventLoop() throws InterruptedException {
            var threwException = new AtomicBoolean(false);
            var latch = new CountDownLatch(1);

            vertx.runOnContext(v -> {
                try {
                    VertxContextHelper.assertOnEventLoop();
                } catch (IllegalStateException e) {
                    threwException.set(true);
                }
                latch.countDown();
            });

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertFalse(threwException.get());
        }

        // Note: assertOnEventLoop behavior when NOT on event loop depends on LaunchMode.
        // In test mode (LaunchMode != NORMAL), it throws IllegalStateException.
        // In production mode (LaunchMode.NORMAL), it only logs a warning.
        // In unit tests without @QuarkusTest, LaunchMode may still be NORMAL,
        // so we just verify the method doesn't crash in either case.
        @Test
        @DisplayName("should handle not being on event loop gracefully")
        void shouldHandleNotOnEventLoopGracefully() {
            // This test verifies the method doesn't crash.
            // Behavior depends on LaunchMode:
            // - NORMAL (production): logs warning, doesn't throw
            // - DEV/TEST: throws IllegalStateException
            // Since LaunchMode may vary, we just verify it handles the situation.
            try {
                VertxContextHelper.assertOnEventLoop();
                // If we get here, we're in production mode (warning logged)
            } catch (IllegalStateException e) {
                // If we get here, we're in dev/test mode (expected)
                assertTrue(e.getMessage().contains("event loop"));
            }
        }
    }
}
