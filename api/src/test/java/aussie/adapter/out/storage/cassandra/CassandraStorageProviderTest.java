package aussie.adapter.out.storage.cassandra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.Test;

import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;

class CassandraStorageProviderTest {

    @Test
    void rejectsMigrationInvocationOnTheVertxEventLoop() throws Exception {
        final var config = mock(StorageAdapterConfig.class);
        when(config.getOrDefault("aussie.storage.cassandra.keyspace", "aussie"))
                .thenReturn("migration_event_loop_test");
        when(config.getOrDefault("aussie.storage.cassandra.run-migrations", "false"))
                .thenReturn("true");

        final var provider = new CassandraStorageProvider();
        final var vertx = Vertx.vertx();
        final var created = new CompletableFuture<Object>();
        try {
            vertx.runOnContext(() -> {
                try {
                    created.complete(provider.createRepository(config));
                } catch (Throwable failure) {
                    created.completeExceptionally(failure);
                }
            });

            final var failure = assertThrows(ExecutionException.class, () -> created.get(5, TimeUnit.SECONDS));
            assertInstanceOf(StorageProviderException.class, failure.getCause());
            assertEquals(
                    "Cassandra migrations cannot run on a Vert.x event loop",
                    failure.getCause().getMessage());
        } finally {
            provider.close();
            vertx.close().await().atMost(Duration.ofSeconds(5));
        }
    }

    @Test
    void rejectsNonPositiveMigrationTimeout() {
        final var config = mock(StorageAdapterConfig.class);
        when(config.getOrDefault("aussie.storage.cassandra.keyspace", "aussie")).thenReturn("migration_timeout_test");
        when(config.getOrDefault("aussie.storage.cassandra.run-migrations", "false"))
                .thenReturn("true");
        when(config.getDuration("aussie.resiliency.cassandra.migration-timeout"))
                .thenReturn(Optional.of(Duration.ZERO));

        final var failure = assertThrows(
                StorageProviderException.class, () -> new CassandraStorageProvider().createRepository(config));

        assertEquals("Cassandra migration timeout must be positive", failure.getMessage());
    }

    @Test
    void rejectsMalformedMigrationTimeout() {
        final var config = mock(StorageAdapterConfig.class);
        when(config.getOrDefault("aussie.storage.cassandra.keyspace", "aussie")).thenReturn("migration_timeout_test");
        when(config.getOrDefault("aussie.storage.cassandra.run-migrations", "false"))
                .thenReturn("true");
        when(config.getDuration("aussie.resiliency.cassandra.migration-timeout"))
                .thenThrow(new DateTimeParseException("Invalid duration", "soon", 0));

        final var failure = assertThrows(
                StorageProviderException.class, () -> new CassandraStorageProvider().createRepository(config));

        assertEquals("Cassandra migration timeout must be an ISO-8601 duration", failure.getMessage());
    }

    @Test
    void interruptsMigrationAfterTimeout() throws Exception {
        final var interrupted = new CountDownLatch(1);

        try (final var executor = Executors.newSingleThreadExecutor()) {
            final var failure = assertThrows(
                    StorageProviderException.class,
                    () -> CassandraStorageProvider.runMigration(
                            () -> {
                                try {
                                    new CountDownLatch(1).await();
                                } catch (InterruptedException e) {
                                    interrupted.countDown();
                                    Thread.currentThread().interrupt();
                                }
                            },
                            Duration.ofSeconds(1),
                            executor));

            assertEquals("Cassandra migration timed out", failure.getMessage());
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        }
    }
}
