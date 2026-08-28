package aussie.e2e;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.e2e.support.E2EHarness;
import aussie.e2e.support.SuiteContext;

@DisplayName("Packaged dependency failure behavior")
final class DependencyFailureE2ETest {

    @Test
    @DisplayName("does not retry a failed upstream request and recovers on the next request")
    void oneShotUpstreamFailureDoesNotRetryAndRecovers() {
        final var harness = E2EHarness.start();
        final var context = SuiteContext.get();
        resetDemo(harness, context);
        injectFailure(harness, context, "/api/health", 503);

        given().baseUri(context.gatewayBaseUri().toString())
                .get("/{serviceId}/api/health", context.demoServiceId())
                .then()
                .statusCode(503);

        assertEquals(1, requestsFor(harness, context, "/api/health"));

        given().baseUri(context.gatewayBaseUri().toString())
                .get("/{serviceId}/api/health", context.demoServiceId())
                .then()
                .statusCode(200);

        assertEquals(2, requestsFor(harness, context, "/api/health"));
    }

    private static void resetDemo(E2EHarness harness, SuiteContext context) {
        given().baseUri(context.demoBaseUri().toString())
                .header("X-Test-Auth", harness.demoTestApiToken())
                .post("/__test__/reset")
                .then()
                .statusCode(204);
    }

    private static void injectFailure(E2EHarness harness, SuiteContext context, String route, int status) {
        given().baseUri(context.demoBaseUri().toString())
                .header("X-Test-Auth", harness.demoTestApiToken())
                .post("/__test__/fail?route=" + route + "&status=" + status)
                .then()
                .statusCode(200);
    }

    private static int requestsFor(E2EHarness harness, SuiteContext context, String path) {
        final Response response = given().baseUri(context.demoBaseUri().toString())
                .header("X-Test-Auth", harness.demoTestApiToken())
                .get("/__test__/state")
                .then()
                .statusCode(200)
                .extract()
                .response();
        final List<Map<String, Object>> requests = response.jsonPath().getList("requests");
        return (int) requests.stream()
                .filter(request -> path.equals(request.get("path")))
                .count();
    }
}
