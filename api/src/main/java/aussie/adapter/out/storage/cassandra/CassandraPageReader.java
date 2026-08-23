package aussie.adapter.out.storage.cassandra;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.Row;

/** Reads every driver page without exposing Cassandra paging details to repositories. */
final class CassandraPageReader {

    private CassandraPageReader() {}

    static <T> CompletionStage<List<T>> readAll(CompletionStage<AsyncResultSet> firstPage, Function<Row, T> mapper) {
        final var values = new ArrayList<T>();
        return readPage(firstPage, mapper, values).thenApply(ignored -> values);
    }

    static CompletionStage<Long> countAll(CompletionStage<AsyncResultSet> firstPage) {
        return countPage(firstPage, 0);
    }

    private static <T> CompletionStage<Void> readPage(
            CompletionStage<AsyncResultSet> pageStage, Function<Row, T> mapper, List<T> values) {
        return pageStage.thenCompose(page -> {
            page.currentPage().forEach(row -> values.add(mapper.apply(row)));
            if (!page.hasMorePages()) {
                return CompletableFuture.completedFuture(null);
            }
            return readPage(page.fetchNextPage(), mapper, values);
        });
    }

    private static CompletionStage<Long> countPage(CompletionStage<AsyncResultSet> pageStage, long count) {
        return pageStage.thenCompose(page -> {
            var total = count;
            for (var ignored : page.currentPage()) {
                total++;
            }
            return page.hasMorePages()
                    ? countPage(page.fetchNextPage(), total)
                    : CompletableFuture.completedFuture(total);
        });
    }
}
