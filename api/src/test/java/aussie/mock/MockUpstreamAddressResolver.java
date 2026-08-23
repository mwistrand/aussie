package aussie.mock;

import java.net.URI;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.quarkus.test.Mock;
import io.smallrye.mutiny.Uni;
import io.vertx.core.net.SocketAddress;
import io.vertx.mutiny.core.Vertx;

import aussie.core.model.auth.GatewaySecurityConfig;
import aussie.core.service.routing.UpstreamAddressResolver;

/** Allows only loopback egress used by Quarkus integration-test backend listeners. */
@Mock
@Alternative
@Priority(1)
@Singleton
public class MockUpstreamAddressResolver extends UpstreamAddressResolver {

    @Inject
    public MockUpstreamAddressResolver(Vertx vertx, GatewaySecurityConfig securityConfig) {
        super(vertx, securityConfig);
    }

    @Override
    public Uni<SocketAddress> resolve(URI uri) {
        if ("localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())) {
            final var port = uri.getPort() == -1 ? defaultPort(uri) : uri.getPort();
            return Uni.createFrom().item(SocketAddress.inetSocketAddress(port, "127.0.0.1"));
        }
        return super.resolve(uri);
    }

    private static int defaultPort(URI uri) {
        return "https".equalsIgnoreCase(uri.getScheme()) || "wss".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
