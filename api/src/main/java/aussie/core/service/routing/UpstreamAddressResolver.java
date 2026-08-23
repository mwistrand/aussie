package aussie.core.service.routing;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import io.vertx.core.net.SocketAddress;
import io.vertx.mutiny.core.Vertx;

import aussie.core.model.auth.GatewaySecurityConfig;

/** Resolves, authorizes, and pins the address used for an outbound connection. */
@ApplicationScoped
public class UpstreamAddressResolver {

    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    private final Vertx vertx;
    private final boolean allowPrivateUpstreams;
    private final Function<String, List<InetAddress>> lookup;

    @Inject
    public UpstreamAddressResolver(Vertx vertx, GatewaySecurityConfig securityConfig) {
        this(vertx, securityConfig.allowPrivateUpstreams(), UpstreamAddressResolver::lookupAll);
    }

    UpstreamAddressResolver(Vertx vertx, boolean allowPrivateUpstreams, Function<String, List<InetAddress>> lookup) {
        this.vertx = vertx;
        this.allowPrivateUpstreams = allowPrivateUpstreams;
        this.lookup = lookup;
    }

    /** Resolve every address and return the exact socket address the client must use. */
    public Uni<SocketAddress> resolve(URI uri) {
        final var host = uri.getHost();
        if (host == null || host.isBlank() || !isSupportedScheme(uri.getScheme())) {
            return Uni.createFrom().failure(new EgressPolicyException("Invalid upstream target"));
        }

        return vertx.executeBlocking(() -> lookup.apply(host), false)
                .ifNoItem()
                .after(LOOKUP_TIMEOUT)
                .failWith(() -> new EgressPolicyException("Upstream DNS lookup timed out"))
                .map(addresses -> authorize(uri, addresses));
    }

    private SocketAddress authorize(URI uri, List<InetAddress> addresses) {
        if (addresses.isEmpty()) {
            throw new EgressPolicyException("Upstream DNS answer is invalid");
        }
        if (addresses.stream().anyMatch(address -> UpstreamAddressPolicy.isBlocked(address, allowPrivateUpstreams))) {
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
            throw new EgressPolicyException("Upstream DNS lookup failed", e);
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
