package aussie.benchmark;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
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

import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.GatewaySnapshot;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.routing.GlobPatternMatcher;

/**
 * Benchmarks for {@link ServiceRegistration#findRoute} covering exact, parameterized, wildcard,
 * no-match, path-rewrite, and glob routes, plus a scaling benchmark that measures worst-case
 * linear scanning as the endpoint count grows.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class RouteMatchingBenchmark {

    @State(Scope.Benchmark)
    public static class GlobPatternState {
        final GlobPatternMatcher matcher = new GlobPatternMatcher();

        @Param({"/api/users/123", "/api//users/123/"})
        String path;

        @Setup
        public void setup() {
            matcher.matches("/api/**", path);
        }
    }

    @State(Scope.Benchmark)
    public static class ExactMatchState {
        ServiceRegistration service;

        @Setup
        public void setup() {
            service = ServiceRegistration.builder("test-service")
                    .baseUrl(URI.create("http://backend.example.com"))
                    .endpoints(List.of(EndpointConfig.publicEndpoint("/users", Set.of("GET", "POST"))))
                    .build();
        }
    }

    @State(Scope.Benchmark)
    public static class ParameterizedMatchState {
        ServiceRegistration service;

        @Setup
        public void setup() {
            service = ServiceRegistration.builder("test-service")
                    .baseUrl(URI.create("http://backend.example.com"))
                    .endpoints(List.of(EndpointConfig.publicEndpoint("/users/{id}/posts/{postId}", Set.of("GET"))))
                    .build();
        }
    }

    @State(Scope.Benchmark)
    public static class WildcardMatchState {
        ServiceRegistration service;

        @Setup
        public void setup() {
            service = ServiceRegistration.builder("test-service")
                    .baseUrl(URI.create("http://backend.example.com"))
                    .endpoints(List.of(
                            EndpointConfig.publicEndpoint("/assets/**", Set.of("GET")),
                            EndpointConfig.publicEndpoint("/api/*", Set.of("GET"))))
                    .build();
        }
    }

    @State(Scope.Benchmark)
    public static class NoMatchState {
        ServiceRegistration service;

        @Setup
        public void setup() {
            var endpoints = new ArrayList<EndpointConfig>();
            for (var i = 0; i < 10; i++) {
                endpoints.add(EndpointConfig.publicEndpoint("/endpoint-" + i + "/{id}", Set.of("GET")));
            }
            service = ServiceRegistration.builder("test-service")
                    .baseUrl(URI.create("http://backend.example.com"))
                    .endpoints(endpoints)
                    .build();
        }
    }

    @State(Scope.Benchmark)
    public static class ScalingState {
        @Param({"1", "10", "50", "100"})
        int endpointCount;

        ServiceRegistration service;
        String matchingPath;

        @Setup
        public void setup() {
            var endpoints = new ArrayList<EndpointConfig>();
            for (var i = 0; i < endpointCount; i++) {
                endpoints.add(EndpointConfig.publicEndpoint("/path-" + i + "/{id}", Set.of("GET")));
            }
            service = ServiceRegistration.builder("test-service")
                    .baseUrl(URI.create("http://backend.example.com"))
                    .endpoints(endpoints)
                    .build();
            // Match the last endpoint to measure worst-case scanning
            matchingPath = "/path-" + (endpointCount - 1) + "/42";
        }
    }

    @State(Scope.Benchmark)
    public static class PathRewriteState {
        ServiceRegistration service;

        @Setup
        public void setup() {
            service = ServiceRegistration.builder("test-service")
                    .baseUrl(URI.create("http://backend.example.com"))
                    .endpoints(List.of(new EndpointConfig(
                            "/v2/users/{id}/profile",
                            Set.of("GET"),
                            EndpointVisibility.PUBLIC,
                            Optional.of("/internal/profiles/{id}"))))
                    .build();
        }
    }

    @Benchmark
    public void findRoute_exactMatch(ExactMatchState state, Blackhole bh) {
        bh.consume(state.service.findRoute("/users", "GET"));
    }

    @Benchmark
    public void findRoute_parameterizedMatch(ParameterizedMatchState state, Blackhole bh) {
        bh.consume(state.service.findRoute("/users/123/posts/456", "GET"));
    }

    @Benchmark
    public void findRoute_wildcardMatch(WildcardMatchState state, Blackhole bh) {
        bh.consume(state.service.findRoute("/assets/images/logo.png", "GET"));
    }

    @Benchmark
    public void findRoute_noMatch(NoMatchState state, Blackhole bh) {
        bh.consume(state.service.findRoute("/nonexistent/path", "GET"));
    }

    @Benchmark
    public void findRoute_scalingWithEndpoints(ScalingState state, Blackhole bh) {
        bh.consume(state.service.findRoute(state.matchingPath, "GET"));
    }

    @Benchmark
    public void findRoute_withPathRewrite(PathRewriteState state, Blackhole bh) {
        bh.consume(state.service.findRoute("/v2/users/42/profile", "GET"));
    }

    @Benchmark
    public void matchGlob_requestPath(GlobPatternState state, Blackhole bh) {
        bh.consume(state.matcher.matches("/api/**", state.path));
    }

    /**
     * Scaling state for the registry's immutable global route snapshot.
     */
    @State(Scope.Benchmark)
    public static class ServiceCountScalingState {
        @Param({"1", "10", "100", "500"})
        int serviceCount;

        GatewaySnapshot snapshot;
        String matchingPath;

        @Setup
        public void setup() {
            final var registrations = new ArrayList<ServiceRegistration>(serviceCount);
            for (var i = 0; i < serviceCount; i++) {
                final var serviceId = "svc-" + i;
                final var registration = ServiceRegistration.builder(serviceId)
                        .baseUrl(URI.create("http://backend.example.com"))
                        .endpoints(List.of(
                                EndpointConfig.publicEndpoint("/" + serviceId + "/api/users/{id}", Set.of("GET"))))
                        .build();
                registrations.add(registration);
            }
            snapshot = GatewaySnapshot.build(registrations);
            matchingPath = "/svc-" + (serviceCount - 1) + "/api/users/42";
        }
    }

    /**
     * Registry gateway hot path. Static first-segment indexing should keep lookup flat as
     * unrelated service count grows.
     */
    @Benchmark
    public void registry_resolveAndMatch(ServiceCountScalingState state, Blackhole bh) {
        bh.consume(state.snapshot.match(state.matchingPath, "GET"));
    }
}
