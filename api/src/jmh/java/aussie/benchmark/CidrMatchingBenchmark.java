package aussie.benchmark;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

import aussie.core.config.TrustedProxyConfig;
import aussie.core.model.auth.AccessControlConfig;
import aussie.core.model.auth.ServiceAccessConfig;
import aussie.core.model.common.SourceIdentifier;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.routing.RouteLookupResult;
import aussie.core.model.routing.RouteMatch;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.service.auth.AccessControlEvaluator;
import aussie.core.service.common.TrustedProxyValidator;

/**
 * Benchmarks for CIDR matching used by trusted proxy validation and access control evaluation.
 * Covers IPv4 prefix lengths, IPv6, scaling with CIDR list size, and global/service
 * access-control intersection.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5)
@Measurement(iterations = 5)
@Fork(2)
public class CidrMatchingBenchmark {

    @State(Scope.Benchmark)
    public static class TrustedProxyState {
        TrustedProxyValidator validator;

        @Setup
        public void setup() {
            var config = new TrustedProxyConfig() {
                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public Optional<List<String>> proxies() {
                    return Optional.of(
                            List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "198.18.0.0/15"));
                }
            };
            validator = new TrustedProxyValidator(config);
        }
    }

    @State(Scope.Benchmark)
    public static class CidrPrefixState {
        TrustedProxyValidator validator8;
        TrustedProxyValidator validator16;
        TrustedProxyValidator validator24;
        TrustedProxyValidator validator32;

        @Setup
        public void setup() {
            validator8 = createValidator(List.of("10.0.0.0/8"));
            validator16 = createValidator(List.of("192.168.0.0/16"));
            validator24 = createValidator(List.of("192.168.1.0/24"));
            validator32 = createValidator(List.of("192.168.1.1/32"));
        }

        private TrustedProxyValidator createValidator(List<String> cidrs) {
            return new TrustedProxyValidator(new TrustedProxyConfig() {
                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public Optional<List<String>> proxies() {
                    return Optional.of(cidrs);
                }
            });
        }
    }

    @State(Scope.Benchmark)
    public static class Ipv6State {
        TrustedProxyValidator validator;

        @Setup
        public void setup() {
            var config = new TrustedProxyConfig() {
                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public Optional<List<String>> proxies() {
                    return Optional.of(List.of("fd00::/8", "fe80::/10", "2001:db8::/32"));
                }
            };
            validator = new TrustedProxyValidator(config);
        }
    }

    @State(Scope.Benchmark)
    public static class ScalingCidrState {
        @Param({"1", "5", "20", "50"})
        int cidrCount;

        TrustedProxyValidator validator;

        @Setup
        public void setup() {
            var cidrs = IntStream.range(0, cidrCount)
                    .mapToObj(i -> "10." + (i % 256) + ".0.0/16")
                    .toList();
            validator = new TrustedProxyValidator(new TrustedProxyConfig() {
                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public Optional<List<String>> proxies() {
                    return Optional.of(cidrs);
                }
            });
        }
    }

    @State(Scope.Benchmark)
    public static class AccessControlState {
        AccessControlEvaluator evaluator;
        RouteLookupResult privateRoute;
        SourceIdentifier allowedIpSource;
        SourceIdentifier deniedSource;
        Optional<ServiceAccessConfig> serviceConfig;

        @Setup
        public void setup() {
            var config = new AccessControlConfig() {
                @Override
                public Optional<List<String>> allowedIps() {
                    return Optional.of(List.of("10.0.0.0/8", "192.168.1.0/24", "172.16.0.1"));
                }

                @Override
                public Optional<List<String>> allowedDomains() {
                    return Optional.empty();
                }

                @Override
                public Optional<List<String>> allowedSubdomains() {
                    return Optional.empty();
                }
            };
            evaluator = new AccessControlEvaluator(config);

            var service = ServiceRegistration.builder("test")
                    .baseUrl("http://backend.example.com")
                    .defaultVisibility(EndpointVisibility.PRIVATE)
                    .build();

            var endpoint = EndpointConfig.privateEndpoint("/test", Set.of("GET"));
            privateRoute = new RouteMatch(service, endpoint, "/test", Map.of());
            allowedIpSource = SourceIdentifier.of("10.10.2.3");
            deniedSource = SourceIdentifier.of("203.0.113.1", "external.example.com");
            serviceConfig = Optional.of(
                    new ServiceAccessConfig(Optional.of(List.of("10.10.0.0/16")), Optional.empty(), Optional.empty()));
        }
    }

    @Benchmark
    public void shouldTrustForwardingHeaders_trusted(TrustedProxyState state, Blackhole bh) {
        bh.consume(state.validator.shouldTrustForwardingHeaders("10.1.2.3"));
    }

    @Benchmark
    public void shouldTrustForwardingHeaders_untrusted(TrustedProxyState state, Blackhole bh) {
        bh.consume(state.validator.shouldTrustForwardingHeaders("203.0.113.5"));
    }

    @Benchmark
    public void matchesCidr_ipv4_slash8(CidrPrefixState state, Blackhole bh) {
        bh.consume(state.validator8.shouldTrustForwardingHeaders("10.1.2.3"));
    }

    @Benchmark
    public void matchesCidr_ipv4_slash16(CidrPrefixState state, Blackhole bh) {
        bh.consume(state.validator16.shouldTrustForwardingHeaders("192.168.1.1"));
    }

    @Benchmark
    public void matchesCidr_ipv4_slash24(CidrPrefixState state, Blackhole bh) {
        bh.consume(state.validator24.shouldTrustForwardingHeaders("192.168.1.100"));
    }

    @Benchmark
    public void matchesCidr_ipv4_slash32(CidrPrefixState state, Blackhole bh) {
        bh.consume(state.validator32.shouldTrustForwardingHeaders("192.168.1.1"));
    }

    @Benchmark
    public void matchesCidr_ipv6(Ipv6State state, Blackhole bh) {
        bh.consume(state.validator.shouldTrustForwardingHeaders("fd00::1"));
    }

    @Benchmark
    public void scalingWithCidrCount(ScalingCidrState state, Blackhole bh) {
        // Non-matching IP forces scanning all CIDRs
        bh.consume(state.validator.shouldTrustForwardingHeaders("203.0.113.5"));
    }

    @Benchmark
    public void accessControl_allowed(AccessControlState state, Blackhole bh) {
        bh.consume(state.evaluator.isAllowed(state.allowedIpSource, state.privateRoute, Optional.empty()));
    }

    @Benchmark
    public void accessControl_denied(AccessControlState state, Blackhole bh) {
        bh.consume(state.evaluator.isAllowed(state.deniedSource, state.privateRoute, state.serviceConfig));
    }

    @Benchmark
    public void accessControl_globalAndServiceIntersection(AccessControlState state, Blackhole bh) {
        bh.consume(state.evaluator.isAllowed(state.allowedIpSource, state.privateRoute, state.serviceConfig));
    }
}
