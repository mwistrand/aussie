package aussie.adapter.out.storage.cassandra;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Vertx;

/** Reads every driver page without exposing Cassandra paging details to repositories. */
final class CassandraPageReader {

    private CassandraPageReader() {}

    static Executor workerExecutor() {
        final var context = Vertx.currentContext();
        if (context != null && context.isWorkerContext()) {
            return command -> context.runOnContext(ignored -> command.run());
        }
        return Infrastructure.getDefaultWorkerPool();
    }

    static <T> CompletionStage<List<T>> readAll(
            CompletionStage<AsyncResultSet> firstPage, Function<Row, T> mapper, Executor executor) {
        final var values = new ArrayList<T>();
        return readPage(firstPage, mapper, values, executor).thenApply(ignored -> values);
    }

    static CompletionStage<Long> countAll(CompletionStage<AsyncResultSet> firstPage, Executor executor) {
        return countPage(firstPage, 0, executor);
    }

    static <T> CompletionStage<List<T>> readPage(
            CompletionStage<AsyncResultSet> firstPage,
            int limit,
            int offset,
            Function<Row, T> mapper,
            Executor executor) {
        final var values = new ArrayList<T>(limit);
        return readPage(firstPage, mapper, values, limit, offset, executor).thenApply(ignored -> values);
    }

    private static <T> CompletionStage<Void> readPage(
            CompletionStage<AsyncResultSet> pageStage, Function<Row, T> mapper, List<T> values, Executor executor) {
        return pageStage.thenComposeAsync(
                page -> {
                    page.currentPage().forEach(row -> values.add(mapper.apply(row)));
                    if (!page.hasMorePages()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return readPage(page.fetchNextPage(), mapper, values, executor);
                },
                executor);
    }

    private static <T> CompletionStage<Void> readPage(
            CompletionStage<AsyncResultSet> pageStage,
            Function<Row, T> mapper,
            List<T> values,
            int limit,
            int offset,
            Executor executor) {
        return pageStage.thenComposeAsync(
                page -> {
                    var remainingOffset = offset;
                    for (var row : page.currentPage()) {
                        if (remainingOffset > 0) {
                            remainingOffset--;
                        } else if (values.size() < limit) {
                            values.add(mapper.apply(row));
                        }
                    }
                    if (values.size() == limit || !page.hasMorePages()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return readPage(page.fetchNextPage(), mapper, values, limit, remainingOffset, executor);
                },
                executor);
    }

    private static CompletionStage<Long> countPage(
            CompletionStage<AsyncResultSet> pageStage, long count, Executor executor) {
        return pageStage.thenComposeAsync(
                page -> {
                    var total = count;
                    for (var ignored : page.currentPage()) {
                        total++;
                    }
                    return page.hasMorePages()
                            ? countPage(page.fetchNextPage(), total, executor)
                            : CompletableFuture.completedFuture(total);
                },
                executor);
    }
}
