package aussie.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

import aussie.core.config.LimitsConfig;
import aussie.core.service.common.RequestSizeValidator;

/** Benchmarks the pure header scan used by the transport request filter. */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class TransportRequestValidationBenchmark {

    @State(Scope.Benchmark)
    public static class HeaderState {

        @Param({"10", "40", "80"})
        int headerCount;

        RequestSizeValidator validator;
        List<Map.Entry<String, String>> headers;

        @Setup
        public void setup() {
            validator = new RequestSizeValidator(new BenchmarkLimitsConfig());
            headers = new ArrayList<>(headerCount);
            for (var i = 0; i < headerCount; i++) {
                headers.add(Map.entry("X-Header-" + i, "value-" + i));
            }
        }
    }

    @Benchmark
    public void validateHeaders(HeaderState state, Blackhole bh) {
        bh.consume(state.validator.validateHeaders(state.headers));
    }

    private static final class BenchmarkLimitsConfig implements LimitsConfig {

        @Override
        public long maxBodySize() {
            return 10 * 1024 * 1024;
        }

        @Override
        public long maxResponseBodySize() {
            return 100 * 1024 * 1024;
        }

        @Override
        public int maxHeaderSize() {
            return 8192;
        }

        @Override
        public int maxTotalHeadersSize() {
            return 32768;
        }

        @Override
        public int maxInitialLineLength() {
            return 4096;
        }

        @Override
        public int maxFormAttributeSize() {
            return 8192;
        }

        @Override
        public int maxFormBufferedBytes() {
            return 1024;
        }

        @Override
        public int maxFormFields() {
            return 256;
        }

        @Override
        public int maxParameters() {
            return 1000;
        }
    }
}
