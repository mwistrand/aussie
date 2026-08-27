package aussie.adapter.out.storage.cassandra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import org.junit.jupiter.api.Test;

class CassandraPageReaderTest {

    @Test
    void readsAllPages() {
        final var firstRow = mock(Row.class);
        final var secondRow = mock(Row.class);
        when(firstRow.getString("id")).thenReturn("first");
        when(secondRow.getString("id")).thenReturn("second");

        final var firstPage = mock(AsyncResultSet.class);
        final var secondPage = mock(AsyncResultSet.class);
        when(firstPage.currentPage()).thenReturn(List.of(firstRow));
        when(firstPage.hasMorePages()).thenReturn(true);
        when(firstPage.fetchNextPage()).thenReturn(CompletableFuture.completedFuture(secondPage));
        when(secondPage.currentPage()).thenReturn(List.of(secondRow));
        when(secondPage.hasMorePages()).thenReturn(false);

        final var result = CassandraPageReader.readAll(
                        CompletableFuture.completedFuture(firstPage), row -> row.getString("id"), Runnable::run)
                .toCompletableFuture()
                .join();
        final var count = CassandraPageReader.countAll(CompletableFuture.completedFuture(firstPage), Runnable::run)
                .toCompletableFuture()
                .join();

        assertEquals(List.of("first", "second"), result);
        assertEquals(2, count);
    }

    @Test
    void readsOnlyRequestedSliceAcrossDriverPages() {
        final var rows = List.of(mock(Row.class), mock(Row.class), mock(Row.class), mock(Row.class));
        for (var index = 0; index < rows.size(); index++) {
            when(rows.get(index).getString("id")).thenReturn("row-" + index);
        }

        final var firstPage = mock(AsyncResultSet.class);
        final var secondPage = mock(AsyncResultSet.class);
        when(firstPage.currentPage()).thenReturn(rows.subList(0, 2));
        when(firstPage.hasMorePages()).thenReturn(true);
        when(firstPage.fetchNextPage()).thenReturn(CompletableFuture.completedFuture(secondPage));
        when(secondPage.currentPage()).thenReturn(rows.subList(2, 4));
        when(secondPage.hasMorePages()).thenReturn(false);

        final var result = CassandraPageReader.readPage(
                        CompletableFuture.completedFuture(firstPage), 2, 1, row -> row.getString("id"), Runnable::run)
                .toCompletableFuture()
                .join();

        assertEquals(List.of("row-1", "row-2"), result);
    }

    @Test
    void mapsEveryPageOnTheCapturedWorkerExecutor() {
        final var firstPage = mock(AsyncResultSet.class);
        final var secondPage = mock(AsyncResultSet.class);
        final var firstRow = mock(Row.class);
        final var secondRow = mock(Row.class);
        when(firstPage.currentPage()).thenReturn(List.of(firstRow));
        when(firstPage.hasMorePages()).thenReturn(true);
        when(firstPage.fetchNextPage()).thenReturn(CompletableFuture.completedFuture(secondPage));
        when(secondPage.currentPage()).thenReturn(List.of(secondRow));
        when(secondPage.hasMorePages()).thenReturn(false);

        final var worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "cassandra-request-worker"));
        try {
            final var driverCompletion = new CompletableFuture<AsyncResultSet>();
            final var result = CassandraPageReader.readAll(
                    driverCompletion,
                    row -> {
                        assertEquals(
                                "cassandra-request-worker",
                                Thread.currentThread().getName());
                        return row;
                    },
                    worker);
            final var driver = new Thread(() -> driverCompletion.complete(firstPage), "cassandra-driver-completion");
            driver.start();
            driver.join();

            assertEquals(
                    List.of(firstRow, secondRow), result.toCompletableFuture().join());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            worker.shutdownNow();
        }
    }
}
