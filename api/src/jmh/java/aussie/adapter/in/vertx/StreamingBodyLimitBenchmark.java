package aussie.adapter.in.vertx;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import io.smallrye.mutiny.Multi;
import io.vertx.mutiny.core.buffer.Buffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
public class StreamingBodyLimitBenchmark {

    @State(Scope.Thread)
    public static class BodyState {
        final Multi<Buffer> body = Multi.createFrom().iterable(Collections.nCopies(256, Buffer.buffer(new byte[4096])));
    }

    @Benchmark
    public void countChunks(BodyState state, Blackhole blackhole) {
        blackhole.consume(StreamingProxyExchange.bounded(state.body, 1024 * 1024, "Request")
                .collect()
                .last()
                .await()
                .indefinitely());
    }
}
