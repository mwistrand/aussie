package aussie.adapter.out.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.net.JksOptions;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.core.config.ResiliencyConfig;

@DisplayName("Outbound HTTP client")
class OutboundHttpClientTest {

    private Vertx vertx;
    private ResiliencyConfig resiliencyConfig;
    private ResiliencyConfig.HttpConfig httpConfig;
    private ResiliencyConfig.HttpConfig.TlsConfig tlsConfig;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        resiliencyConfig = mock(ResiliencyConfig.class);
        httpConfig = mock(ResiliencyConfig.HttpConfig.class);
        tlsConfig = mock(ResiliencyConfig.HttpConfig.TlsConfig.class);
        when(resiliencyConfig.http()).thenReturn(httpConfig);
        final var jwksConfig = mock(ResiliencyConfig.JwksConfig.class);
        when(resiliencyConfig.jwks()).thenReturn(jwksConfig);
        when(jwksConfig.maxConnections()).thenReturn(3);
        when(httpConfig.connectTimeout()).thenReturn(Duration.ofSeconds(2));
        when(httpConfig.maxConnectionsPerHost()).thenReturn(7);
        when(httpConfig.maxConnections()).thenReturn(19);
        when(httpConfig.tls()).thenReturn(tlsConfig);
        when(tlsConfig.protocols()).thenReturn(List.of("TLSv1.3", "TLSv1.2"));
        when(tlsConfig.handshakeTimeout()).thenReturn(Duration.ofSeconds(2));
        when(tlsConfig.trustCertificates()).thenReturn(Optional.empty());
        when(tlsConfig.clientCertificate()).thenReturn(Optional.empty());
        when(tlsConfig.clientKey()).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        vertx.close().await().atMost(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("uses bounded pools and a fail-closed TLS policy")
    void usesSafeOptions() {
        final var options = OutboundHttpClient.options(httpConfig);

        assertFalse(options.isTrustAll());
        assertTrue(options.isVerifyHost());
        assertFalse(options.isFollowRedirects());
        assertEquals(2_000, options.getConnectTimeout());
        assertEquals(7, options.getMaxPoolSize());
        assertEquals(19, options.getMaxWaitQueueSize());
        assertEquals(Set.of("TLSv1.3", "TLSv1.2"), options.getEnabledSecureTransportProtocols());
    }

    @Test
    @DisplayName("rejects obsolete TLS protocols")
    void rejectsObsoleteProtocols() {
        when(tlsConfig.protocols()).thenReturn(List.of("TLSv1.1"));

        assertThrows(IllegalArgumentException.class, () -> OutboundHttpClient.options(httpConfig));
    }

    @Test
    @DisplayName("rejects a non-positive total connection limit")
    void rejectsInvalidTotalConnectionLimit() {
        when(httpConfig.maxConnections()).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> OutboundHttpClient.options(httpConfig));
    }

    @Test
    @DisplayName("requires both halves of an mTLS identity")
    void rejectsIncompleteMtlsIdentity() {
        when(tlsConfig.clientCertificate()).thenReturn(Optional.of("client.pem"));

        assertThrows(IllegalArgumentException.class, () -> OutboundHttpClient.options(httpConfig));
    }

    @Test
    @DisplayName("rejects unreadable trust material at startup")
    void rejectsUnreadableTrustMaterial() {
        when(tlsConfig.trustCertificates()).thenReturn(Optional.of(List.of("missing-ca.pem")));

        assertThrows(IllegalArgumentException.class, () -> OutboundHttpClient.options(httpConfig));
    }

    @Test
    @DisplayName("verifies a trusted certificate against the logical host of a pinned address")
    void verifiesTrustedCertificateAndHostname() throws Exception {
        final var tempDirectory = Files.createTempDirectory("aussie-tls-test");
        final var keyStorePath = tempDirectory.resolve("server.jks");
        final var keytool = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "keytool");
        final var keytoolExit = new ProcessBuilder(
                        keytool.toString(),
                        "-genkeypair",
                        "-alias",
                        "server",
                        "-keyalg",
                        "RSA",
                        "-dname",
                        "CN=localhost",
                        "-ext",
                        "SAN=dns:localhost",
                        "-validity",
                        "1",
                        "-storetype",
                        "JKS",
                        "-keystore",
                        keyStorePath.toString(),
                        "-storepass",
                        "password",
                        "-keypass",
                        "password")
                .inheritIO()
                .start()
                .waitFor();
        assertEquals(0, keytoolExit);
        final var keyStore = KeyStore.getInstance("JKS");
        try (var source = Files.newInputStream(keyStorePath)) {
            keyStore.load(source, "password".toCharArray());
        }
        final var certificate =
                keyStore.getCertificate(Collections.list(keyStore.aliases()).getFirst());
        final var trustPath = Files.createTempFile("aussie-test-ca", ".pem");
        final var pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                        .encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        Files.writeString(trustPath, pem, StandardCharsets.US_ASCII);
        when(tlsConfig.trustCertificates()).thenReturn(Optional.of(List.of(trustPath.toString())));
        final var server = vertx.createHttpServer(new HttpServerOptions()
                        .setSsl(true)
                        .setKeyCertOptions(new JksOptions()
                                .setPath(keyStorePath.toString())
                                .setPassword("password")))
                .requestHandler(request -> request.response().endAndForget("ok"))
                .listen(0)
                .await()
                .atMost(Duration.ofSeconds(5));
        final var client = new OutboundHttpClient(vertx, resiliencyConfig);

        try {
            final var address =
                    io.vertx.mutiny.core.net.SocketAddress.inetSocketAddress(server.actualPort(), "127.0.0.1");
            final var response = client.webClient()
                    .request(HttpMethod.GET, address, server.actualPort(), "localhost", "/")
                    .ssl(true)
                    .send()
                    .await()
                    .atMost(Duration.ofSeconds(5));
            assertEquals(200, response.statusCode());

            assertThrows(RuntimeException.class, () -> client.webClient()
                    .request(HttpMethod.GET, address, server.actualPort(), "wrong.example", "/")
                    .ssl(true)
                    .send()
                    .await()
                    .atMost(Duration.ofSeconds(5)));

            when(tlsConfig.trustCertificates()).thenReturn(Optional.empty());
            final var untrustedClient = new OutboundHttpClient(vertx, resiliencyConfig);
            try {
                assertThrows(RuntimeException.class, () -> untrustedClient
                        .webClient()
                        .request(HttpMethod.GET, address, server.actualPort(), "localhost", "/")
                        .ssl(true)
                        .send()
                        .await()
                        .atMost(Duration.ofSeconds(5)));
            } finally {
                untrustedClient.close();
            }

            final var plaintextServer = vertx.createHttpServer()
                    .requestHandler(request -> request.response().endAndForget("not tls"))
                    .listen(0)
                    .await()
                    .atMost(Duration.ofSeconds(5));
            try {
                final var plaintextAddress = io.vertx.mutiny.core.net.SocketAddress.inetSocketAddress(
                        plaintextServer.actualPort(), "127.0.0.1");
                assertThrows(RuntimeException.class, () -> client.webClient()
                        .request(HttpMethod.GET, plaintextAddress, plaintextServer.actualPort(), "localhost", "/")
                        .ssl(true)
                        .send()
                        .await()
                        .atMost(Duration.ofSeconds(5)));
            } finally {
                plaintextServer.close().await().atMost(Duration.ofSeconds(5));
            }
        } finally {
            client.close();
            server.close().await().atMost(Duration.ofSeconds(5));
            Files.deleteIfExists(trustPath);
            Files.deleteIfExists(keyStorePath);
            Files.deleteIfExists(tempDirectory);
        }
    }
}
