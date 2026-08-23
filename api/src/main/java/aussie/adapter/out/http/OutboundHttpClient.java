package aussie.adapter.out.http;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.vertx.core.http.HttpClient;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;

import aussie.core.config.ResiliencyConfig;
import aussie.core.port.out.OutboundHttpClients;

/** Application-owned, bounded connection pools sharing one verified-TLS policy. */
@ApplicationScoped
public class OutboundHttpClient implements OutboundHttpClients {

    private final WebClient webClient;
    private final WebClient jwksWebClient;
    private final HttpClient httpClient;

    @Inject
    public OutboundHttpClient(Vertx vertx, ResiliencyConfig resiliencyConfig) {
        final var options = options(resiliencyConfig.http());
        final var client = vertx.createHttpClient(options);
        this.httpClient = client.getDelegate();
        this.webClient = WebClient.wrap(client, options);
        this.jwksWebClient = WebClient.create(
                vertx,
                new WebClientOptions(options)
                        .setMaxPoolSize(resiliencyConfig.jwks().maxConnections()));
    }

    static WebClientOptions options(ResiliencyConfig.HttpConfig config) {
        final var tls = config.tls();
        final var protocols = new HashSet<>(tls.protocols());
        if (protocols.isEmpty() || protocols.stream().anyMatch(protocol -> !protocol.matches("TLSv1\\.[23]"))) {
            throw new IllegalArgumentException("Egress TLS protocols must contain only TLSv1.2 or TLSv1.3");
        }
        if (config.connectTimeout().isZero()
                || config.connectTimeout().isNegative()
                || tls.handshakeTimeout().isZero()
                || tls.handshakeTimeout().isNegative()
                || config.maxConnectionsPerHost() < 1) {
            throw new IllegalArgumentException("Egress timeouts and pool size must be positive");
        }

        final var options = new WebClientOptions()
                .setConnectTimeout(Math.toIntExact(config.connectTimeout().toMillis()))
                .setSslHandshakeTimeout(tls.handshakeTimeout().toMillis())
                .setSslHandshakeTimeoutUnit(TimeUnit.MILLISECONDS)
                .setMaxPoolSize(config.maxConnectionsPerHost())
                .setKeepAlive(true)
                .setIdleTimeout(75)
                .setTcpNoDelay(true)
                .setReusePort(true)
                .setFollowRedirects(false)
                .setSsl(true)
                .setTrustAll(false)
                .setVerifyHost(true)
                .setEnabledSecureTransportProtocols(protocols);

        tls.trustCertificates().filter(paths -> !paths.isEmpty()).ifPresent(paths -> {
            final var trust = new PemTrustOptions();
            paths.forEach(path -> trust.addCertPath(requireReadableFile(path, "trust certificate")));
            options.setTrustOptions(trust);
        });

        final var certificate = tls.clientCertificate();
        final var key = tls.clientKey();
        if (certificate.isPresent() != key.isPresent()) {
            throw new IllegalArgumentException("Egress mTLS requires both client-certificate and client-key");
        }
        certificate.ifPresent(path -> options.setKeyCertOptions(new PemKeyCertOptions()
                .setCertPath(requireReadableFile(path, "client certificate"))
                .setKeyPath(requireReadableFile(key.orElseThrow(), "client key"))));
        return options;
    }

    private static String requireReadableFile(String value, String name) {
        try {
            final var path = Path.of(value);
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new IllegalArgumentException("Egress TLS " + name + " must be a readable regular file");
            }
            return value;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Egress TLS " + name + " path is invalid", e);
        }
    }

    @Override
    public WebClient webClient() {
        return webClient;
    }

    @Override
    public HttpClient httpClient() {
        return httpClient;
    }

    @Override
    public WebClient jwksWebClient() {
        return jwksWebClient;
    }

    @PreDestroy
    void close() {
        webClient.close();
        jwksWebClient.close();
    }
}
