package aussie.e2e.support;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.cassandra.CassandraContainer;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Boots Cassandra + Redis + demo + Aussie in a shared Docker network for the
 * duration of a JUnit launcher session. Idempotent: {@link #start()} is a
 * no-op if already started.
 *
 * <p>Container hostnames inside the network:
 * <ul>
 *   <li>{@code cassandra:9042}</li>
 *   <li>{@code redis:6379}</li>
 *   <li>{@code demo:3000}</li>
 *   <li>{@code api:8080}</li>
 * </ul>
 *
 * <p>Host-side access: {@link #gatewayBaseUri()} returns the mapped port for
 * {@code api:8080}; {@link #demoBaseUri()} returns the mapped port for
 * {@code demo:3000} (useful for tests that want to verify demo state directly
 * via {@code /__test__/state}).
 */
public final class E2EHarness {

    private static final Logger LOG = LoggerFactory.getLogger(E2EHarness.class);
    private static final String BOOTSTRAP_KEY = "aussie_v1_" + generateRandomSecret(32);
    private static final String JWS_SIGNING_KEY = generateSigningKey();
    // Per-run shared secret expected by the demo's /__test__/* endpoints in the
    // X-Test-Auth header. Defence-in-depth against DEMO_TEST_API_ENABLED leaking
    // into a non-test environment.
    private static final String DEMO_TEST_API_TOKEN = generateRandomSecret(24);

    private static E2EHarness instance;

    private final Network network;
    private final CassandraContainer cassandra;
    private final GenericContainer<?> redis;
    private final GenericContainer<?> demo;
    private final GenericContainer<?> api;
    private final GenericContainer<?> replica;
    private final GenericContainer<?> alertmanager;
    private final GenericContainer<?> prometheus;
    private final Path alertmanagerConfig;
    private String replicaLogs = "";

    private E2EHarness() {
        Path apiProjectDir = resolveApiProjectDir();
        Path repoRootDir = resolveRepoRootDir(apiProjectDir);
        Path demoDir = repoRootDir.resolve("demo");
        Path translationConfig = demoDir.resolve("translation-config.json");
        Path monitoringDir = repoRootDir.resolve("monitoring");

        if (!Files.exists(apiProjectDir.resolve("build/quarkus-app/quarkus-run.jar"))) {
            throw new IllegalStateException("build/quarkus-app missing - run :api:quarkusBuild before :e2eTest. Path: "
                    + apiProjectDir.resolve("build/quarkus-app"));
        }
        if (!Files.exists(demoDir.resolve("server.js"))) {
            throw new IllegalStateException("demo/server.js not found at " + demoDir);
        }
        if (!Files.exists(demoDir.resolve("node_modules"))) {
            throw new IllegalStateException(
                    "demo/node_modules missing - run `npm install` in demo/ before :e2eTest. Path: " + demoDir);
        }
        // Bind-mounting demo/ exposes any stale Next.js dev-mode state to the
        // containerized node process. The .next/dev/lock left over from a prior
        // `npm run dev` makes the new instance abort immediately. Wipe the dev
        // working directory before each suite — production-mode .next artifacts
        // (build manifests, etc.) stay intact.
        clearNextDevState(demoDir.resolve(".next").resolve("dev"));

        this.network = Network.newNetwork();

        this.cassandra = new CassandraContainer(DockerImageName.parse("cassandra:4.1"))
                .withNetwork(network)
                .withNetworkAliases("cassandra")
                .withEnv("CASSANDRA_DC", "datacenter1")
                .withEnv("CASSANDRA_CLUSTER_NAME", "aussie-cluster")
                .withEnv("HEAP_NEWSIZE", "128M")
                .withEnv("MAX_HEAP_SIZE", "512M")
                .withStartupTimeout(Duration.ofMinutes(3))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("e2e.cassandra")));

        this.redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withNetwork(network)
                .withNetworkAliases("redis")
                .withExposedPorts(6379)
                .withCommand("redis-server", "--appendonly", "yes")
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("e2e.redis")));

        this.demo = new GenericContainer<>(DockerImageName.parse("node:20-alpine"))
                .withNetwork(network)
                .withNetworkAliases("demo")
                .withExposedPorts(3000)
                .withWorkingDirectory("/app")
                .withFileSystemBind(demoDir.toAbsolutePath().toString(), "/app", BindMode.READ_WRITE)
                .withEnv("NODE_ENV", "development")
                .withEnv("HOSTNAME", "0.0.0.0")
                .withEnv("DEMO_TEST_API_ENABLED", "true")
                .withEnv("DEMO_TEST_API_TOKEN", DEMO_TEST_API_TOKEN)
                .withCommand("node", "server.js")
                .waitingFor(Wait.forHttp("/api/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("e2e.demo")));

        var apiImage = new ImageFromDockerfile("aussie-api-e2e", false)
                .withFileFromPath("build/quarkus-app", apiProjectDir.resolve("build/quarkus-app"))
                .withFileFromPath("Dockerfile", apiProjectDir.resolve("src/main/docker/Dockerfile.e2e"));

        this.api = apiContainer(apiImage, translationConfig, "api", "e2e.api");
        this.replica = apiContainer(apiImage, translationConfig, "api-replica", "e2e.api.replica");
        this.alertmanagerConfig = createE2eAlertmanagerConfig(apiProjectDir);
        this.alertmanager = new GenericContainer<>(DockerImageName.parse("prom/alertmanager:v0.26.0"))
                .withNetwork(network)
                .withNetworkAliases("alertmanager")
                .withExposedPorts(9093)
                .withFileSystemBind(
                        alertmanagerConfig.toAbsolutePath().toString(),
                        "/etc/alertmanager/alertmanager.yml",
                        BindMode.READ_ONLY)
                .withCommand("--config.file=/etc/alertmanager/alertmanager.yml", "--storage.path=/alertmanager")
                .waitingFor(Wait.forHttp("/-/ready").forStatusCode(200))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("e2e.alertmanager")));
        this.prometheus = new GenericContainer<>(DockerImageName.parse("prom/prometheus:v2.48.0"))
                .withNetwork(network)
                .withNetworkAliases("prometheus")
                .withExposedPorts(9090)
                .withFileSystemBind(
                        monitoringDir
                                .resolve("prometheus/prometheus.yml")
                                .toAbsolutePath()
                                .toString(),
                        "/etc/prometheus/prometheus.yml",
                        BindMode.READ_ONLY)
                .withFileSystemBind(
                        monitoringDir
                                .resolve("prometheus/alerts")
                                .toAbsolutePath()
                                .toString(),
                        "/etc/prometheus/alerts",
                        BindMode.READ_ONLY)
                .withCommand(
                        "--config.file=/etc/prometheus/prometheus.yml",
                        "--storage.tsdb.path=/prometheus",
                        "--web.enable-lifecycle")
                .waitingFor(Wait.forHttp("/-/ready").forStatusCode(200))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("e2e.prometheus")));
    }

    private GenericContainer<?> apiContainer(
            ImageFromDockerfile apiImage, Path translationConfig, String networkAlias, String logName) {
        return new GenericContainer<>(apiImage)
                .withNetwork(network)
                .withNetworkAliases(networkAlias)
                .withExposedPorts(8080)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(translationConfig), "/etc/aussie/translation-config.json")
                .withEnv("QUARKUS_PROFILE", "dev")
                .withEnv("CASSANDRA_CONTACT_POINTS", "cassandra:9042")
                .withEnv("QUARKUS_REDIS_HOSTS", "redis://redis:6379")
                .withEnv("DEMO_APP_URL", "http://demo:3000")
                .withEnv("AUSSIE_GATEWAY_CORS_ENABLED", "true")
                .withEnv("AUSSIE_GATEWAY_CORS_ALLOWED_ORIGINS", "http://localhost:3000,http://127.0.0.1:3000")
                .withEnv("AUSSIE_GATEWAY_CORS_ALLOW_CREDENTIALS", "true")
                .withEnv("AUSSIE_AUTH_TOKEN_TRANSLATION_ENABLED", "true")
                .withEnv("AUSSIE_AUTH_TOKEN_TRANSLATION_PROVIDER", "config")
                .withEnv("AUSSIE_AUTH_TOKEN_TRANSLATION_CONFIG_PATH", "/etc/aussie/translation-config.json")
                .withEnv("AUSSIE_ADMIN_MUTATIONS_DISTRIBUTED", "true")
                .withEnv("AUSSIE_BOOTSTRAP_ENABLED", "true")
                .withEnv("AUSSIE_BOOTSTRAP_KEY", BOOTSTRAP_KEY)
                .withEnv("AUSSIE_JWS_SIGNING_KEY", JWS_SIGNING_KEY)
                .withEnv("AUSSIE_STORAGE_REPOSITORY_PROVIDER", "cassandra")
                // Dev profile turns on JWT route-auth which would treat the
                // bootstrap API key as a JWT and short-circuit with 401. Later
                // steps that actually exercise route-auth flip this on per-test.
                .withEnv("AUSSIE_AUTH_ROUTE_AUTH_ENABLED", "false")
                .withEnv("CASSANDRA_RUN_MIGRATIONS", "true")
                .waitingFor(
                        Wait.forHttp("/q/health/ready").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger(logName)));
    }

    public static synchronized E2EHarness start() {
        if (instance != null) {
            return instance;
        }
        var harness = new E2EHarness();
        // Publish before starting containers so that stop() can dump logs and
        // release whatever did come up if a later step throws.
        instance = harness;
        try {
            LOG.info("E2EHarness: starting cassandra + redis...");
            harness.cassandra.start();
            harness.redis.start();
            LOG.info("E2EHarness: starting demo...");
            harness.demo.start();
            LOG.info("E2EHarness: starting api...");
            harness.api.start();
            LOG.info("E2EHarness: ready. gateway={} demo={}", harness.gatewayBaseUri(), harness.demoBaseUri());
            return harness;
        } catch (RuntimeException e) {
            try {
                stop(true);
            } catch (RuntimeException teardown) {
                e.addSuppressed(teardown);
            }
            throw e;
        }
    }

    public static synchronized void stop(boolean dumpLogs) {
        if (instance == null) {
            return;
        }
        E2EHarness h = instance;
        if (dumpLogs) {
            try {
                h.dumpLogs();
            } catch (Exception e) {
                LOG.warn("Failed to dump container logs", e);
            }
        }
        List<Throwable> failures = new ArrayList<>();
        // Reverse start order: API replicas -> demo -> redis -> cassandra -> network.
        runQuietly(failures, "api-replica", h.replica::stop);
        runQuietly(failures, "api", h.api::stop);
        runQuietly(failures, "prometheus", h.prometheus::stop);
        runQuietly(failures, "alertmanager", h.alertmanager::stop);
        runQuietly(failures, "demo", h.demo::stop);
        runQuietly(failures, "redis", h.redis::stop);
        runQuietly(failures, "cassandra", h.cassandra::stop);
        runQuietly(failures, "network", h.network::close);
        SuiteContext.clear();
        instance = null;
        if (!failures.isEmpty()) {
            RuntimeException combined = new RuntimeException("E2EHarness teardown encountered errors");
            failures.forEach(combined::addSuppressed);
            throw combined;
        }
    }

    private static void runQuietly(List<Throwable> failures, String label, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            LOG.warn("E2EHarness: {} teardown raised", label, t);
            failures.add(t);
        }
    }

    public String bootstrapKey() {
        return BOOTSTRAP_KEY;
    }

    public String demoTestApiToken() {
        return DEMO_TEST_API_TOKEN;
    }

    public URI gatewayBaseUri() {
        return URI.create("http://" + api.getHost() + ":" + api.getMappedPort(8080));
    }

    public URI demoBaseUri() {
        return URI.create("http://" + demo.getHost() + ":" + demo.getMappedPort(3000));
    }

    public synchronized void startObservabilityStack() {
        if (prometheus.isRunning()) {
            return;
        }
        Testcontainers.exposeHostPorts(9099);
        alertmanager.start();
        prometheus.start();
    }

    public URI prometheusBaseUri() {
        return URI.create("http://" + prometheus.getHost() + ":" + prometheus.getMappedPort(9090));
    }

    public URI alertmanagerBaseUri() {
        return URI.create("http://" + alertmanager.getHost() + ":" + alertmanager.getMappedPort(9093));
    }

    public synchronized void startReplica() {
        if (!replica.isRunning()) {
            replica.start();
        }
    }

    public URI replicaBaseUri() {
        return URI.create("http://" + replica.getHost() + ":" + replica.getMappedPort(8080));
    }

    public synchronized void stopPrimary() {
        api.stop();
    }

    public synchronized void restartPrimary() {
        api.stop();
        api.start();
        SuiteContext.updateGatewayBaseUri(gatewayBaseUri());
    }

    public synchronized void stopReplica() {
        try {
            if (replica.isRunning()) {
                replicaLogs = replica.getLogs();
            }
        } catch (RuntimeException e) {
            LOG.warn("Failed to retain replica logs", e);
        }
        replica.stop();
    }

    /** Restart the packaged gateway while keeping its dependencies and network alive. */
    public synchronized void restartApi() {
        api.stop();
        api.start();
        SuiteContext.updateGatewayBaseUri(gatewayBaseUri());
    }

    public String cassandraHost() {
        return cassandra.getHost();
    }

    public int cassandraPort() {
        return cassandra.getMappedPort(9042);
    }

    public String adminAuditStream() {
        try {
            return redis.execInContainer("redis-cli", "XRANGE", "aussie:admin:audit", "-", "+")
                    .getStdout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not read the admin audit stream", e);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the admin audit stream", e);
        }
    }

    private void dumpLogs() throws IOException {
        Path apiProjectDir = resolveApiProjectDir();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outDir = apiProjectDir.resolve("build/e2e-logs/" + stamp);
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("api.log"), api.getLogs());
        Files.writeString(outDir.resolve("api-replica.log"), replica.isRunning() ? replica.getLogs() : replicaLogs);
        Files.writeString(outDir.resolve("prometheus.log"), prometheus.isRunning() ? prometheus.getLogs() : "");
        Files.writeString(outDir.resolve("alertmanager.log"), alertmanager.isRunning() ? alertmanager.getLogs() : "");
        Files.writeString(outDir.resolve("demo.log"), demo.getLogs());
        Files.writeString(outDir.resolve("redis.log"), redis.getLogs());
        Files.writeString(outDir.resolve("cassandra.log"), cassandra.getLogs());
        LOG.info("E2EHarness: container logs written to {}", outDir);
    }

    private static void clearNextDevState(Path nextDevDir) {
        if (!Files.exists(nextDevDir)) {
            return;
        }
        try (var stream = Files.walk(nextDevDir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort; lock files may briefly resist
                }
            });
        } catch (IOException e) {
            LOG.warn("Failed to clear stale Next.js dev state at {}", nextDevDir, e);
        }
    }

    private static Path createE2eAlertmanagerConfig(Path apiProjectDir) {
        try {
            var path = apiProjectDir.resolve("build/e2e-alertmanager.yml");
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    """
                    global: {}
                    route:
                      group_by: ['alertname']
                      group_wait: 1s
                      group_interval: 1s
                      repeat_interval: 1h
                      receiver: e2e-webhook
                    receivers:
                      - name: e2e-webhook
                        webhook_configs:
                          - url: 'http://host.testcontainers.internal:9099/alerts'
                    """);
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the E2E Alertmanager configuration", e);
        }
    }

    private static Path resolveApiProjectDir() {
        String configured = System.getProperty("aussie.e2e.apiProjectDir");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        // Fall back to current working directory (when run from api/).
        return Paths.get("").toAbsolutePath();
    }

    private static Path resolveRepoRootDir(Path apiProjectDir) {
        String configured = System.getProperty("aussie.e2e.repoRootDir");
        if (configured != null && !configured.isBlank()) {
            return Paths.get(configured);
        }
        return apiProjectDir.getParent();
    }

    private static String generateRandomSecret(int byteLength) {
        byte[] buf = new byte[byteLength];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String generateSigningKey() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return Base64.getEncoder()
                    .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to generate the E2E signing key", e);
        }
    }
}
