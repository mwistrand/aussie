package aussie.benchmark;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

import aussie.adapter.out.http.Rfc7239ForwardedHeaderBuilder;
import aussie.core.model.gateway.GatewayRequest;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.ForwardedHeaderBuilder;
import aussie.core.port.out.ForwardedHeaderBuilderProvider;
import aussie.core.service.gateway.ProxyRequestPreparer;

/**
 * Benchmarks the request- and response-side header pipeline in
 * {@link ProxyRequestPreparer}. Header copying happens 4-6 times per request today; this
 * suite catches regressions in the per-header allocation cost (toLowerCase, List.copyOf)
 * and the {@code Connection} header lookup.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class HeaderPipelineBenchmark {

    private static final String[] BASE_HEADERS = {
        "Accept",
        "Accept-Encoding",
        "Accept-Language",
        "Authorization",
        "Cache-Control",
        "Cookie",
        "Origin",
        "Referer",
        "User-Agent",
        "X-Request-ID",
        "X-Forwarded-For",
        "X-Forwarded-Proto",
        "X-Real-IP",
        "DNT",
        "Pragma",
        "Sec-Fetch-Dest",
        "Sec-Fetch-Mode",
        "Sec-Fetch-Site",
        "Sec-CH-UA",
        "Sec-CH-UA-Mobile",
        "If-None-Match",
        "If-Modified-Since",
        "Range",
        "Upgrade-Insecure-Requests",
        "X-Custom-1",
        "X-Custom-2",
        "X-Custom-3",
        "X-Custom-4",
        "X-Custom-5",
        "X-Custom-6",
        "X-Custom-7",
        "X-Custom-8",
        "X-Custom-9",
        "X-Custom-10",
        "X-Trace-ID",
        "X-Span-ID",
        "X-Tenant-ID",
        "X-Client-Version",
        "X-Feature-Flags",
        "X-Session-ID"
    };

    @State(Scope.Benchmark)
    public static class PrepareState {

        @Param({"10", "20", "40"})
        int headerCount;

        @Param({"false", "true"})
        boolean withConnectionHeader;

        ProxyRequestPreparer preparer;
        GatewayRequest request;
        RouteMatch route;

        @Setup
        public void setup() {
            preparer = new ProxyRequestPreparer(new RealBuilderProvider(new Rfc7239ForwardedHeaderBuilder()));

            final Map<String, List<String>> headers = new HashMap<>();
            for (var i = 0; i < headerCount; i++) {
                headers.put(BASE_HEADERS[i % BASE_HEADERS.length], List.of("value-" + i));
            }
            headers.put("Host", List.of("api.example.com"));
            headers.put("Content-Length", List.of("0"));
            if (withConnectionHeader) {
                // Declare two custom hop-by-hop names plus the standard "keep-alive" directive.
                headers.put("Connection", List.of("keep-alive, X-Custom-1, X-Custom-2"));
            }

            request = new GatewayRequest(
                    "GET",
                    "/api/users/42",
                    headers,
                    URI.create("https://api.example.com/svc/api/users/42"),
                    new byte[0],
                    "203.0.113.5");

            final var service = ServiceRegistration.builder("svc")
                    .baseUrl(URI.create("http://upstream.example.com"))
                    .endpoints(List.of(EndpointConfig.publicEndpoint("/api/users/{id}", Set.of("GET"))))
                    .build();
            final var endpoint = EndpointConfig.publicEndpoint("/api/users/{id}", Set.of("GET"));
            route = new RouteMatch(service, endpoint, "/api/users/42", Map.of());
        }
    }

    @State(Scope.Benchmark)
    public static class ResponseFilterState {

        @Param({"10", "20", "40"})
        int headerCount;

        @Param({"false", "true"})
        boolean withConnectionHeader;

        ProxyRequestPreparer preparer;
        Map<String, List<String>> responseHeaders;

        @Setup
        public void setup() {
            preparer = new ProxyRequestPreparer(new RealBuilderProvider(new Rfc7239ForwardedHeaderBuilder()));

            responseHeaders = new HashMap<>();
            for (var i = 0; i < headerCount; i++) {
                responseHeaders.put(BASE_HEADERS[i % BASE_HEADERS.length], List.of("value-" + i));
            }
            responseHeaders.put("Transfer-Encoding", List.of("chunked"));
            if (withConnectionHeader) {
                responseHeaders.put("Connection", List.of("close, X-Trace-ID"));
            }
        }
    }

    @Benchmark
    public void prepare(PrepareState state, Blackhole bh) {
        bh.consume(state.preparer.prepare(state.request, state.route, Optional.empty()));
    }

    @Benchmark
    public void filterResponseHeaders(ResponseFilterState state, Blackhole bh) {
        bh.consume(state.preparer.filterResponseHeaders(state.responseHeaders));
    }

    private static final class RealBuilderProvider implements ForwardedHeaderBuilderProvider {
        private final ForwardedHeaderBuilder builder;

        RealBuilderProvider(ForwardedHeaderBuilder builder) {
            this.builder = builder;
        }

        @Override
        public ForwardedHeaderBuilder getBuilder() {
            return builder;
        }
    }
}
