package aussie.benchmark;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import aussie.core.cache.CaffeineLocalCache;

/**
 * Benchmarks for {@link CaffeineLocalCache}. Covers hit/miss lookup latency, write cost with
 * and without TTL jitter, and a mixed 90/10 read/write concurrent throughput workload. Class
 * is not annotated with a single {@code @BenchmarkMode} because the concurrent benchmark uses
 * throughput mode while the rest use average time.
 */
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class LocalCacheBenchmark {

    @State(Scope.Benchmark)
    public static class HitState {
        CaffeineLocalCache<String, String> cache;
        String key;

        @Setup
        public void setup() {
            cache = new CaffeineLocalCache<>(Duration.ofSeconds(60), 10_000, 0.1);
            for (var i = 0; i < 1000; i++) {
                cache.put("key-" + i, "value-" + i);
            }
            key = "key-500";
        }
    }

    @State(Scope.Benchmark)
    public static class MissState {
        CaffeineLocalCache<String, String> cache;

        @Setup
        public void setup() {
            cache = new CaffeineLocalCache<>(Duration.ofSeconds(60), 10_000, 0.1);
            for (var i = 0; i < 1000; i++) {
                cache.put("key-" + i, "value-" + i);
            }
        }
    }

    @State(Scope.Thread)
    public static class WriteWithJitterState {
        CaffeineLocalCache<String, String> cache;
        long counter;

        @Setup(Level.Iteration)
        public void setup() {
            cache = new CaffeineLocalCache<>(Duration.ofSeconds(60), 10_000, 0.1);
            counter = 0L;
        }
    }

    @State(Scope.Thread)
    public static class WriteWithoutJitterState {
        CaffeineLocalCache<String, String> cache;
        long counter;

        @Setup(Level.Iteration)
        public void setup() {
            cache = new CaffeineLocalCache<>(Duration.ofSeconds(60), 10_000, 0.0);
            counter = 0L;
        }
    }

    @State(Scope.Benchmark)
    public static class ConcurrentState {
        CaffeineLocalCache<String, String> cache;

        @Setup
        public void setup() {
            cache = new CaffeineLocalCache<>(Duration.ofSeconds(60), 10_000, 0.1);
            for (var i = 0; i < 5000; i++) {
                cache.put("key-" + i, "value-" + i);
            }
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void get_hit(HitState state, Blackhole bh) {
        bh.consume(state.cache.get(state.key));
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void get_miss(MissState state, Blackhole bh) {
        bh.consume(state.cache.get("nonexistent-key"));
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void put_withJitter(WriteWithJitterState state, Blackhole bh) {
        state.cache.put("write-key-" + state.counter++, "value");
        bh.consume(state.counter);
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public void put_withoutJitter(WriteWithoutJitterState state, Blackhole bh) {
        state.cache.put("write-key-" + state.counter++, "value");
        bh.consume(state.counter);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Threads(8)
    public void throughput_concurrent(ConcurrentState state, Blackhole bh) {
        var rand = ThreadLocalRandom.current();
        if (rand.nextInt(10) < 9) {
            // 90% reads
            bh.consume(state.cache.get("key-" + rand.nextInt(5000)));
        } else {
            // 10% writes
            state.cache.put("key-" + rand.nextInt(5000), "updated-value");
        }
    }
}
