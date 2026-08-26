package aussie.benchmark;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.core.config.TrustedProxyConfig;
import aussie.core.service.common.TrustedProxyValidator;

/** Benchmarks canonical client identity and scheme resolution at the inbound trust boundary. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class ClientContextResolverBenchmark {

    @State(Scope.Thread)
    public static class ResolverState {

        @Param({"FORWARDED", "X_FORWARDED_FOR", "X_REAL_IP"})
        String headerFormat;

        @Param({"1", "4", "16"})
        int hopCount;

        ClientContextResolver resolver;
        String forwarded;
        String xForwardedFor;
        String xRealIp;
        String xForwardedProto;

        @Setup
        public void setup() {
            final var config = new TrustedProxyConfig() {
                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public Optional<List<String>> proxies() {
                    return Optional.of(List.of("10.0.0.0/8"));
                }
            };
            resolver = new ClientContextResolver(new TrustedProxyValidator(config));

            final var addresses = IntStream.range(0, hopCount)
                    .mapToObj(i -> i == 0 ? "203.0.113.5" : "10.0.0." + (i + 1))
                    .toList();
            switch (headerFormat) {
                case "FORWARDED" -> forwarded = addresses.stream()
                        .map(address -> "for=" + address + ";proto=https")
                        .reduce((left, right) -> left + ", " + right)
                        .orElseThrow();
                case "X_FORWARDED_FOR" -> {
                    xForwardedFor = String.join(", ", addresses);
                    xForwardedProto = IntStream.range(0, hopCount)
                            .mapToObj(i -> "https")
                            .reduce((left, right) -> left + ", " + right)
                            .orElseThrow();
                }
                case "X_REAL_IP" -> {
                    xRealIp = "203.0.113.5";
                    xForwardedProto = "https";
                }
                default -> throw new IllegalArgumentException("Unsupported header format: " + headerFormat);
            }
        }
    }

    @Benchmark
    public void resolve(ResolverState state, Blackhole blackhole) {
        blackhole.consume(state.resolver.resolve(
                "10.0.0.1", state.forwarded, state.xForwardedFor, state.xRealIp, state.xForwardedProto));
    }
}
