package aussie.adapter.out.storage.cassandra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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
                        CompletableFuture.completedFuture(firstPage), row -> row.getString("id"))
                .toCompletableFuture()
                .join();
        final var count = CassandraPageReader.countAll(CompletableFuture.completedFuture(firstPage))
                .toCompletableFuture()
                .join();

        assertEquals(List.of("first", "second"), result);
        assertEquals(2, count);
    }
}
