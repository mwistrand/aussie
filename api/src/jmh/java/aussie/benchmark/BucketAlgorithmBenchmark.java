package aussie.benchmark;

import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import aussie.core.model.ratelimit.BucketAlgorithm;
import aussie.core.model.ratelimit.BucketState;
import aussie.core.model.ratelimit.EffectiveRateLimit;

/**
 * Benchmarks for the token bucket rate limiting algorithm. Covers the allowed, rejected, and
 * refill branches plus the read-only status computation so that regressions in the hot path
 * of every rate-limited request are visible.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class BucketAlgorithmBenchmark {

    @State(Scope.Thread)
    public static class AllowedState {
        BucketAlgorithm algorithm;
        EffectiveRateLimit limit;
        BucketState fullBucket;
        long nowMillis;

        @Setup
        public void setup() {
            algorithm = BucketAlgorithm.getInstance();
            limit = EffectiveRateLimit.of(1000, 60);
            nowMillis = System.currentTimeMillis();
            fullBucket = new BucketState(limit.burstCapacity(), nowMillis);
        }
    }

    @State(Scope.Thread)
    public static class RejectedState {
        BucketAlgorithm algorithm;
        EffectiveRateLimit limit;
        BucketState emptyBucket;
        long nowMillis;

        @Setup
        public void setup() {
            algorithm = BucketAlgorithm.getInstance();
            limit = EffectiveRateLimit.of(1000, 60);
            nowMillis = System.currentTimeMillis();
            emptyBucket = new BucketState(0, nowMillis);
        }
    }

    @State(Scope.Thread)
    public static class RefillState {
        BucketAlgorithm algorithm;
        EffectiveRateLimit limit;
        BucketState drainedBucket;
        long nowMillis;

        @Setup
        public void setup() {
            algorithm = BucketAlgorithm.getInstance();
            limit = EffectiveRateLimit.of(1000, 60);
            // Bucket was drained 5 seconds ago, should refill ~83 tokens
            final var drainTime = System.currentTimeMillis() - 5000;
            drainedBucket = new BucketState(0, drainTime);
            nowMillis = System.currentTimeMillis();
        }
    }

    @State(Scope.Thread)
    public static class BurstCapState {
        BucketAlgorithm algorithm;
        EffectiveRateLimit limit;
        BucketState longIdleBucket;
        long nowMillis;

        @Setup
        public void setup() {
            algorithm = BucketAlgorithm.getInstance();
            limit = EffectiveRateLimit.of(1000, 60);
            // Bucket was drained a long time ago, refill should cap at burst capacity
            final var drainTime = System.currentTimeMillis() - 600_000;
            longIdleBucket = new BucketState(0, drainTime);
            nowMillis = System.currentTimeMillis();
        }
    }

    @State(Scope.Thread)
    public static class StatusState {
        BucketAlgorithm algorithm;
        EffectiveRateLimit limit;
        BucketState partialBucket;
        long nowMillis;

        @Setup
        public void setup() {
            algorithm = BucketAlgorithm.getInstance();
            limit = EffectiveRateLimit.of(1000, 60);
            nowMillis = System.currentTimeMillis();
            partialBucket = new BucketState(500, nowMillis);
        }
    }

    @Benchmark
    public void checkAndConsume_allowed(AllowedState state, Blackhole bh) {
        bh.consume(state.algorithm.checkAndConsume(state.fullBucket, state.limit, state.nowMillis));
    }

    @Benchmark
    public void checkAndConsume_rejected(RejectedState state, Blackhole bh) {
        bh.consume(state.algorithm.checkAndConsume(state.emptyBucket, state.limit, state.nowMillis));
    }

    @Benchmark
    public void checkAndConsume_withRefill(RefillState state, Blackhole bh) {
        bh.consume(state.algorithm.checkAndConsume(state.drainedBucket, state.limit, state.nowMillis));
    }

    @Benchmark
    public void checkAndConsume_burstCapacityCap(BurstCapState state, Blackhole bh) {
        bh.consume(state.algorithm.checkAndConsume(state.longIdleBucket, state.limit, state.nowMillis));
    }

    @Benchmark
    public void computeStatus_readOnly(StatusState state, Blackhole bh) {
        bh.consume(state.algorithm.computeStatus(state.partialBucket, state.limit, state.nowMillis));
    }
}
