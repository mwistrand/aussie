package aussie.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.adapter.in.rest.ControlPlaneContract;
import aussie.e2e.support.SuiteContext;

/** Verifies that a pre-version-header CLI can still use the new server. */
@DisplayName("Control-plane compatibility")
final class ControlPlaneCompatibilityE2ETest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("accepts an older CLI request without a contract-version header")
    void acceptsOldCliRequest() throws Exception {
        var context = SuiteContext.get();
        var request = HttpRequest.newBuilder(context.gatewayBaseUri().resolve("/admin/capabilities"))
                .header("Authorization", "Bearer " + context.bootstrapKey())
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        assertEquals(
                ControlPlaneContract.VERSION,
                response.headers()
                        .firstValue(ControlPlaneContract.VERSION_HEADER)
                        .orElse(null));

        JsonNode capabilities = JSON.readTree(response.body());
        assertEquals(
                ControlPlaneContract.VERSION,
                capabilities.path("contractVersion").asText());
        assertEquals(
                ControlPlaneContract.VERSION,
                capabilities.path("supportedContractVersions").get(0).asText());
    }
}
