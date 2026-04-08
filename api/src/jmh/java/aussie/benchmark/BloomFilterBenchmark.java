package aussie.benchmark;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmarks for the "definitely not revoked" bloom filter fast path used by token revocation
 * checks. Measures lookup cost on populated, empty, and variably-sized filters, plus insertion
 * cost so regressions in sizing or hashing are caught before they hit production traffic.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class BloomFilterBenchmark {

    private static final double FPP = 0.001;

    @State(Scope.Benchmark)
    public static class PopulatedFilterState {
        BloomFilter<CharSequence> filter;
        String presentJti;
        String absentJti;

        @Setup
        public void setup() {
            filter = BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 10_000, FPP);
            for (var i = 0; i < 10_000; i++) {
                filter.put("jti-" + i);
            }
            presentJti = "jti-5000";
            absentJti = "jti-absent-" + UUID.randomUUID();
        }
    }

    @State(Scope.Benchmark)
    public static class EmptyFilterState {
        BloomFilter<CharSequence> filter;

        @Setup
        public void setup() {
            filter = BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 10_000, FPP);
        }
    }

    @State(Scope.Benchmark)
    public static class ScalingState {
        @Param({"100", "1000", "10000", "100000"})
        int entryCount;

        BloomFilter<CharSequence> filter;
        String absentJti;

        @Setup
        public void setup() {
            filter = BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), entryCount, FPP);
            for (var i = 0; i < entryCount; i++) {
                filter.put("jti-" + i);
            }
            absentJti = "jti-absent-" + UUID.randomUUID();
        }
    }

    @State(Scope.Thread)
    public static class InsertionState {
        BloomFilter<CharSequence> filter;
        long counter;

        @Setup(Level.Iteration)
        public void setup() {
            filter = BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), 100_000, FPP);
            counter = 0L;
        }
    }

    @Benchmark
    public void definitelyNotRevoked_negative(PopulatedFilterState state, Blackhole bh) {
        bh.consume(!state.filter.mightContain(state.absentJti));
    }

    @Benchmark
    public void definitelyNotRevoked_positive(PopulatedFilterState state, Blackhole bh) {
        bh.consume(!state.filter.mightContain(state.presentJti));
    }

    @Benchmark
    public void definitelyNotRevoked_emptyFilter(EmptyFilterState state, Blackhole bh) {
        bh.consume(!state.filter.mightContain("any-jti"));
    }

    @Benchmark
    public void scalingWithEntries(ScalingState state, Blackhole bh) {
        bh.consume(!state.filter.mightContain(state.absentJti));
    }

    @Benchmark
    public void addRevokedJti(InsertionState state, Blackhole bh) {
        bh.consume(state.filter.put("jti-insert-" + state.counter++));
    }
}
