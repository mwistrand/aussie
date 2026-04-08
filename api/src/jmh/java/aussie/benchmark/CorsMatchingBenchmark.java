package aussie.benchmark;

import java.util.ArrayList;
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

import aussie.core.model.common.CorsConfig;

/**
 * Benchmarks for CORS origin and method matching. Measures the exact-match, wildcard subdomain,
 * and no-match paths so that changes to origin matching do not silently regress the preflight
 * hot path.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class CorsMatchingBenchmark {

    @State(Scope.Benchmark)
    public static class ExactMatchState {
        CorsConfig config;

        @Setup
        public void setup() {
            config = CorsConfig.builder()
                    .allowedOrigins(
                            "https://app.example.com",
                            "https://admin.example.com",
                            "https://dashboard.example.com",
                            "https://portal.example.com",
                            "https://api.example.com")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .build();
        }
    }

    @State(Scope.Benchmark)
    public static class WildcardState {
        CorsConfig config;

        @Setup
        public void setup() {
            config = CorsConfig.builder()
                    .allowedOrigins("https://app.example.com", "*.example.com", "*.internal.example.com")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .build();
        }
    }

    @State(Scope.Benchmark)
    public static class NoMatchState {
        CorsConfig config;

        @Setup
        public void setup() {
            var origins = new ArrayList<String>();
            for (var i = 0; i < 20; i++) {
                origins.add("https://site-" + i + ".example.com");
            }
            config = CorsConfig.builder()
                    .allowedOrigins(origins)
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                    .build();
        }
    }

    @State(Scope.Benchmark)
    public static class MethodState {
        CorsConfig config;

        @Setup
        public void setup() {
            config = CorsConfig.builder()
                    .allowedOrigins("*")
                    .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")
                    .build();
        }
    }

    @Benchmark
    public void isOriginAllowed_exactMatch(ExactMatchState state, Blackhole bh) {
        bh.consume(state.config.isOriginAllowed("https://dashboard.example.com"));
    }

    @Benchmark
    public void isOriginAllowed_wildcardSubdomain(WildcardState state, Blackhole bh) {
        bh.consume(state.config.isOriginAllowed("https://staging.internal.example.com"));
    }

    @Benchmark
    public void isOriginAllowed_noMatch(NoMatchState state, Blackhole bh) {
        bh.consume(state.config.isOriginAllowed("https://evil.attacker.com"));
    }

    @Benchmark
    public void isMethodAllowed(MethodState state, Blackhole bh) {
        bh.consume(state.config.isMethodAllowed("POST"));
    }
}
