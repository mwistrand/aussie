package aussie.benchmark;

import java.net.URI;
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
import aussie.core.model.routing.GatewaySnapshot;
import aussie.core.model.service.ServiceRegistration;

/**
 * Benchmarks for CORS origin, method, and requested-header matching.
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
    public static class HeaderState {
        CorsConfig config;

        @Setup
        public void setup() {
            config = CorsConfig.builder()
                    .allowedHeaders("Content-Type", "Authorization", "X-Requested-With", "Accept", "Origin")
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

    @State(Scope.Benchmark)
    public static class ServiceOriginState {
        GatewaySnapshot snapshot;

        @Setup
        public void setup() {
            final var services = new ArrayList<ServiceRegistration>();
            for (var i = 0; i < 1_000; i++) {
                services.add(ServiceRegistration.builder("service-" + i)
                        .baseUrl(URI.create("http://192.0.2.10"))
                        .corsConfig(CorsConfig.builder()
                                .allowedOrigins("https://service-" + i + ".example.com")
                                .build())
                        .build());
            }
            snapshot = GatewaySnapshot.build(services);
        }
    }

    @Benchmark
    public void isOriginAllowed_exactMatch(ExactMatchState state, Blackhole bh) {
        bh.consume(state.config.isOriginAllowed("https://dashboard.example.com"));
    }

    @Benchmark
    public void areHeadersAllowed(HeaderState state, Blackhole bh) {
        bh.consume(state.config.areHeadersAllowed("authorization, content-type"));
    }

    @Benchmark
    public void isOriginAllowed_noMatch(NoMatchState state, Blackhole bh) {
        bh.consume(state.config.isOriginAllowed("https://evil.attacker.com"));
    }

    @Benchmark
    public void isMethodAllowed(MethodState state, Blackhole bh) {
        bh.consume(state.config.isMethodAllowed("POST"));
    }

    @Benchmark
    public void findServiceCorsAmongThousandOrigins(ServiceOriginState state, Blackhole bh) {
        bh.consume(state.snapshot.corsConfigForOrigin("https://service-999.example.com"));
    }
}
