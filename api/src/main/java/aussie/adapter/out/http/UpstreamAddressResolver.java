package aussie.adapter.out.http;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import io.vertx.core.net.SocketAddress;
import io.vertx.mutiny.core.Vertx;

import aussie.core.config.ResiliencyConfig;
import aussie.core.model.auth.GatewaySecurityConfig;
import aussie.core.service.routing.UpstreamAddressPolicy;

/** Resolves, authorizes, and pins the address used for an outbound connection. */
@ApplicationScoped
public class UpstreamAddressResolver {

    private static final Duration DEFAULT_LOOKUP_TIMEOUT = Duration.ofSeconds(5);
    private static final int DEFAULT_MAX_CONCURRENT_LOOKUPS = 64;
    private static final MeterRegistry TEST_METER_REGISTRY = new SimpleMeterRegistry();

    private final boolean allowPrivateUpstreams;
    private final ExecutorService blockingExecutor;
    private final Function<String, Uni<List<InetAddress>>> lookup;
    private final Duration lookupTimeout;
    private final Semaphore permits;
    private final Timer lookupLatency;
    private final Counter lookupTimeouts;
    private final Counter lookupRejections;
    private final Counter lookupFailures;
    private final Counter answerRejections;

    @Inject
    public UpstreamAddressResolver(
            GatewaySecurityConfig securityConfig, ResiliencyConfig resiliencyConfig, MeterRegistry meterRegistry) {
        this(
                securityConfig.allowPrivateUpstreams(),
                resiliencyConfig.dns().lookupTimeout(),
                resiliencyConfig.dns().maxConcurrentLookups(),
                UpstreamAddressResolver::lookupAll,
                meterRegistry);
    }

    /** Compatibility constructor for test and extension subclasses that override {@link #resolve(URI)}. */
    public UpstreamAddressResolver(Vertx vertx, GatewaySecurityConfig securityConfig) {
        this(vertx, securityConfig.allowPrivateUpstreams(), UpstreamAddressResolver::lookupAll);
    }

    UpstreamAddressResolver(Vertx vertx, boolean allowPrivateUpstreams, Function<String, List<InetAddress>> lookup) {
        this(vertx, allowPrivateUpstreams, DEFAULT_LOOKUP_TIMEOUT, DEFAULT_MAX_CONCURRENT_LOOKUPS, lookup);
    }

    UpstreamAddressResolver(
            Vertx vertx,
            boolean allowPrivateUpstreams,
            Duration lookupTimeout,
            int maxConcurrentLookups,
            Function<String, List<InetAddress>> lookup) {
        this(allowPrivateUpstreams, lookupTimeout, maxConcurrentLookups, lookup, TEST_METER_REGISTRY);
    }

    private UpstreamAddressResolver(
            boolean allowPrivateUpstreams,
            Duration lookupTimeout,
            int maxConcurrentLookups,
            Function<String, List<InetAddress>> lookup,
            MeterRegistry meterRegistry) {
        this.allowPrivateUpstreams = allowPrivateUpstreams;
        this.blockingExecutor = Executors.newFixedThreadPool(maxConcurrentLookups, daemonThreadFactory());
        this.lookupTimeout = lookupTimeout;
        this.permits = new Semaphore(maxConcurrentLookups);
        this.lookup = host -> Uni.createFrom().item(() -> lookup.apply(host)).runSubscriptionOn(blockingExecutor);
        this.lookupLatency = Timer.builder("aussie.dns.lookup.latency").register(meterRegistry);
        this.lookupTimeouts = Counter.builder("aussie.dns.lookup.timeouts").register(meterRegistry);
        this.lookupRejections = Counter.builder("aussie.dns.lookup.rejections").register(meterRegistry);
        this.lookupFailures = Counter.builder("aussie.dns.lookup.failures").register(meterRegistry);
        this.answerRejections = Counter.builder("aussie.dns.answer.rejections").register(meterRegistry);
    }

    /** Resolve every address and return the exact socket address the client must use. */
    public Uni<SocketAddress> resolve(URI uri) {
        final var host = uri.getHost();
        if (host == null || host.isBlank() || !isSupportedScheme(uri.getScheme())) {
            return Uni.createFrom().failure(new EgressPolicyException("Invalid upstream target"));
        }

        return Uni.createFrom()
                .deferred(() -> {
                    if (!permits.tryAcquire()) {
                        lookupRejections.increment();
                        return Uni.createFrom()
                                .failure(new EgressPolicyException(
                                        "DNS resolver capacity exhausted",
                                        new RejectedExecutionException("DNS resolver capacity exhausted")));
                    }
                    final var startedAt = System.nanoTime();
                    return lookup.apply(host)
                            .ifNoItem()
                            .after(lookupTimeout)
                            .failWith(DnsLookupTimeoutException::new)
                            .onFailure(DnsLookupTimeoutException.class)
                            .invoke(ignored -> lookupTimeouts.increment())
                            .onFailure()
                            .invoke(failure -> {
                                if (!(failure instanceof DnsLookupTimeoutException)) {
                                    lookupFailures.increment();
                                }
                            })
                            .eventually(() -> {
                                permits.release();
                                lookupLatency.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
                            });
                })
                .map(addresses -> authorize(uri, addresses));
    }

    private SocketAddress authorize(URI uri, List<InetAddress> addresses) {
        if (addresses.isEmpty()) {
            answerRejections.increment();
            throw new EgressPolicyException("Upstream DNS answer is invalid");
        }
        if (addresses.stream().anyMatch(address -> UpstreamAddressPolicy.isBlocked(address, allowPrivateUpstreams))) {
            answerRejections.increment();
            throw new EgressPolicyException("Upstream address denied by egress policy");
        }
        return SocketAddress.inetSocketAddress(port(uri), addresses.getFirst().getHostAddress());
    }

    private static boolean isSupportedScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme)
                || "ws".equalsIgnoreCase(scheme)
                || "wss".equalsIgnoreCase(scheme);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static List<InetAddress> lookupAll(String host) {
        try {
            return Arrays.asList(InetAddress.getAllByName(host));
        } catch (UnknownHostException e) {
            throw new DnsLookupException("Upstream DNS lookup failed", e);
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        return Thread.ofPlatform().daemon().name("aussie-dns-", 0).factory();
    }

    @PreDestroy
    void close() {
        blockingExecutor.shutdownNow();
    }

    private static class DnsLookupException extends EgressPolicyException {
        private DnsLookupException(String message) {
            super(message);
        }

        private DnsLookupException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class DnsLookupTimeoutException extends DnsLookupException {
        private DnsLookupTimeoutException() {
            super("Upstream DNS lookup timed out");
        }
    }

    /** Stable failure type for denied or unresolvable egress. */
    public static class EgressPolicyException extends RuntimeException {
        public EgressPolicyException(String message) {
            super(message);
        }

        public EgressPolicyException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
