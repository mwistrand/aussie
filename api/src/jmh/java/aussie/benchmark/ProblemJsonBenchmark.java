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

import aussie.adapter.in.problem.ProblemDetail;
import aussie.adapter.in.problem.ProblemJson;

/**
 * Benchmarks for {@link ProblemJson#serialize(ProblemDetail)}. The native Vert.x
 * error path runs this for every 4xx/5xx response, so regressions here are a
 * tax on the failure path under load (rate-limit storms, upstream outages).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class ProblemJsonBenchmark {

    @State(Scope.Benchmark)
    public static class BaseState {
        ProblemDetail badGateway;
        ProblemDetail serviceNotFound;
        ProblemDetail rateLimitedMinimal;
        ProblemDetail rateLimitedFull;

        @Setup
        public void setup() {
            badGateway = ProblemDetail.badGateway("Upstream unavailable");
            serviceNotFound = ProblemDetail.serviceNotFound("user-service");
            rateLimitedMinimal = ProblemDetail.tooManyRequests("Throttled", 30L);
            rateLimitedFull = ProblemDetail.tooManyRequests("Throttled", 30L, 100L, 0L, 1_700_000_000L);
        }
    }

    @Benchmark
    public void serializeBadGateway(BaseState s, Blackhole bh) {
        bh.consume(ProblemJson.serialize(s.badGateway));
    }

    @Benchmark
    public void serializeServiceNotFound(BaseState s, Blackhole bh) {
        bh.consume(ProblemJson.serialize(s.serviceNotFound));
    }

    @Benchmark
    public void serializeRateLimitedMinimal(BaseState s, Blackhole bh) {
        bh.consume(ProblemJson.serialize(s.rateLimitedMinimal));
    }

    @Benchmark
    public void serializeRateLimitedFull(BaseState s, Blackhole bh) {
        bh.consume(ProblemJson.serialize(s.rateLimitedFull));
    }
}
