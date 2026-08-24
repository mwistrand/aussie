package aussie.e2e.support;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.awaitility.Awaitility;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives the per-session lifecycle for the e2e suite: starts the Testcontainer
 * harness, registers the demo service via the admin API, and tears everything
 * down at session close.
 *
 * <p>Registered as a JUnit Platform service via
 * {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}.
 */
public final class SuiteBootstrapListener implements LauncherSessionListener {

    private static final Logger LOG = LoggerFactory.getLogger(SuiteBootstrapListener.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INTERNAL_DEMO_URL = "http://demo:3000";

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private E2EHarness harness;
    // JUnit Platform does not guarantee single-threaded listener delivery, so
    // FailureTracker may write from a test-executor thread while teardown reads
    // from the launcher thread.
    private final AtomicBoolean anyFailures = new AtomicBoolean();

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            harness = E2EHarness.start();
            JsonNode servicePayload = readServicePayload();
            String serviceId = extractServiceId(servicePayload);
            registerDemoService(servicePayload);
            SuiteContext.install(
                    harness.gatewayBaseUri(),
                    harness.demoBaseUri(),
                    harness.bootstrapKey(),
                    serviceId,
                    harness.cassandraHost(),
                    harness.cassandraPort());

            session.getLauncher().registerTestExecutionListeners(new FailureTracker(this::markFailed));
            LOG.info("E2E suite bootstrap complete. Gateway at {}", harness.gatewayBaseUri());
        } catch (Exception e) {
            anyFailures.set(true);
            LOG.error("E2E suite bootstrap failed", e);
            // Gradle suppresses test-executor stderr by default; stash the full cause
            // on disk where the developer can find it.
            Path crashFile = writeCrashReport(e);
            try {
                E2EHarness.stop(true);
            } catch (Exception ignored) {
                // best-effort
            }
            throw new IllegalStateException(
                    "E2E suite bootstrap failed: " + e.getMessage() + " (full trace at " + crashFile + ")", e);
        }
    }

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        try {
            E2EHarness.stop(anyFailures.get());
        } catch (Exception e) {
            LOG.warn("E2EHarness teardown raised", e);
        }
    }

    private void markFailed() {
        anyFailures.set(true);
    }

    private JsonNode readServicePayload() throws IOException {
        Path path = resolveDemoServicePayload();
        return JSON.readTree(Files.readString(path));
    }

    private String extractServiceId(JsonNode payload) {
        JsonNode id = payload.get("serviceId");
        if (id == null || !id.isTextual()) {
            throw new IllegalStateException("serviceId missing from " + resolveDemoServicePayload());
        }
        return id.asText();
    }

    private void registerDemoService(JsonNode payload) throws IOException, InterruptedException {
        ObjectNode body = ((ObjectNode) payload).deepCopy();
        // Force the in-network address regardless of what the on-disk payload
        // declares; the file lives in demo/ for the host-side `make api` flow.
        body.put("baseUrl", INTERNAL_DEMO_URL);

        URI target = harness.gatewayBaseUri().resolve("/admin/services");
        HttpRequest request = HttpRequest.newBuilder(target)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + harness.bootstrapKey())
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = sendWithRetry(request);
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Demo service registration failed: HTTP " + response.statusCode() + " body=" + response.body());
        }
        LOG.info("Registered demo-service via {}", target);
    }

    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        return Awaitility.await("admin api ready")
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .until(
                        () -> http.send(request, HttpResponse.BodyHandlers.ofString()),
                        // Any 5xx may be transient while the gateway is still warming up.
                        r -> r != null && r.statusCode() < 500);
    }

    private Path writeCrashReport(Throwable cause) {
        try {
            String apiDir = System.getProperty("aussie.e2e.apiProjectDir");
            Path base = (apiDir == null || apiDir.isBlank()) ? Path.of("") : Path.of(apiDir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path outDir = base.resolve("build/e2e-logs/bootstrap-" + stamp);
            Files.createDirectories(outDir);
            Path crash = outDir.resolve("bootstrap-failure.txt");
            try (StringWriter sw = new StringWriter();
                    PrintWriter pw = new PrintWriter(sw)) {
                cause.printStackTrace(pw);
                Files.writeString(crash, sw.toString());
            }
            return crash;
        } catch (Exception nested) {
            LOG.warn("Failed to write bootstrap crash report", nested);
            return Path.of("(crash report write failed)");
        }
    }

    private Path resolveDemoServicePayload() {
        String repoRoot = System.getProperty("aussie.e2e.repoRootDir");
        Path base = (repoRoot == null || repoRoot.isBlank())
                ? Path.of("").toAbsolutePath().getParent()
                : Path.of(repoRoot);
        return base.resolve("demo/aussie-service.json");
    }
}
