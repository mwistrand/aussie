package aussie.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import aussie.e2e.support.E2EHarness;
import aussie.e2e.support.SuiteContext;

final class ObservabilityE2ETest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void packagedMetricsRulesAndAlertDeliveryAreConnected() throws Exception {
        var deliveries = new AtomicInteger();
        var deliveryBody = new AtomicReference<String>();
        var receiver = HttpServer.create(new InetSocketAddress(9099), 0);
        receiver.createContext("/alerts", exchange -> {
            deliveryBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            deliveries.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        receiver.start();

        try {
            var harness = E2EHarness.start();
            harness.startObservabilityStack();
            var context = SuiteContext.get();

            awaitQuery(harness.prometheusBaseUri(), "up{job=\"aussie\"}");
            get(context.gatewayBaseUri().resolve("/" + context.demoServiceId() + "/api/health"));
            var metrics = get(context.gatewayBaseUri().resolve("/q/metrics"));
            assertTrue(metrics.body().contains("aussie_requests_total"));

            awaitQuery(harness.prometheusBaseUri(), "aussie_requests_total");

            var rules =
                    get(harness.prometheusBaseUri().resolve("/api/v1/rules")).body();
            assertTrue(rules.contains("aussie-slos"));
            assertTrue(rules.contains("AussieAvailabilityErrorBudgetBurn"));

            var alertmanagers = get(harness.prometheusBaseUri().resolve("/api/v1/alertmanagers"))
                    .body();
            assertTrue(alertmanagers.contains("activeAlertmanagers"));
            assertTrue(alertmanagers.contains("alertmanager"));

            post(
                    harness.alertmanagerBaseUri().resolve("/api/v2/alerts"),
                    """
                    [{"labels":{"alertname":"AussieE2EAlert","severity":"critical"},
                      "annotations":{"summary":"packaged alert delivery"}}]
                    """);
            Awaitility.await("Alertmanager webhook delivery")
                    .atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> {
                        assertTrue(deliveries.get() > 0);
                        assertTrue(deliveryBody.get().contains("AussieE2EAlert"));
                    });
        } finally {
            receiver.stop(0);
        }
    }

    private void awaitQuery(URI prometheus, String query) {
        Awaitility.await("Prometheus query: " + query)
                .atMost(Duration.ofMinutes(2))
                .pollInterval(Duration.ofSeconds(2))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    var response = get(prometheus.resolve(
                            "/api/v1/query?query=" + URLEncoder.encode(query, StandardCharsets.UTF_8)));
                    var json = JSON.readTree(response.body());
                    assertTrue(response.statusCode() == 200);
                    assertTrue("success".equals(json.path("status").asText()));
                    assertTrue(json.path("data").path("result").isArray());
                    assertTrue(json.path("data").path("result").size() > 0);
                });
    }

    private HttpResponse<String> get(URI uri) {
        try {
            return http.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("HTTP request failed: " + uri, e);
        }
    }

    private void post(URI uri, String body) {
        try {
            var response = http.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode() == 200 || response.statusCode() == 202);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("HTTP POST failed: " + uri, e);
        }
    }
}
