package aussie.benchmark;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.mockito.Mockito;
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

import aussie.adapter.out.telemetry.SecurityEventDispatcher;
import aussie.adapter.out.telemetry.SecurityMonitor;
import aussie.adapter.out.telemetry.TelemetryConfig;

/** Measures per-client security tracking with a small and full cache. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class SecurityMonitorBenchmark {

    @State(Scope.Thread)
    public static class FixtureState {

        @Param({"1", "10000"})
        int clientCount;

        SecurityMonitor monitor;
        String[] clients;
        int clientIndex;

        @Setup
        public void setup() {
            final var dos = Mockito.mock(TelemetryConfig.SecurityConfig.DosDetectionConfig.class);
            final var security = Mockito.mock(TelemetryConfig.SecurityConfig.class);
            Mockito.when(security.enabled()).thenReturn(true);
            Mockito.when(security.rateLimitWindow()).thenReturn(Duration.ofMinutes(1));
            Mockito.when(security.rateLimitThreshold()).thenReturn(Integer.MAX_VALUE);
            Mockito.when(security.maxTrackedClients()).thenReturn(10_000);
            Mockito.when(security.clientTrackingTtl()).thenReturn(Duration.ofMinutes(10));
            Mockito.when(security.dosDetection()).thenReturn(dos);

            final var config = Mockito.mock(TelemetryConfig.class);
            Mockito.when(config.enabled()).thenReturn(true);
            Mockito.when(config.security()).thenReturn(security);

            monitor = new SecurityMonitor(config, Mockito.mock(SecurityEventDispatcher.class));
            clients = IntStream.range(0, clientCount)
                    .mapToObj(index -> "192.0.2." + index)
                    .toArray(String[]::new);
        }

        String nextClient() {
            final var client = clients[clientIndex];
            clientIndex = (clientIndex + 1) % clients.length;
            return client;
        }
    }

    @Benchmark
    public void recordRequest(FixtureState state) {
        state.monitor.recordRequest(state.nextClient(), "service", false);
    }
}
