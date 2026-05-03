package aussie;

import static io.restassured.RestAssured.given;

import java.util.HashMap;
import java.util.Map;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration test that exercises the rate-limit filter chain against the real
 * Quarkus container with rate limiting enabled.
 *
 * <p>The default test profile disables rate limiting to avoid event-loop
 * blocking on most tests. This profile turns it on with a tight burst capacity
 * so we can hit the 429 path within a reasonable iteration count. Branch
 * coverage of {@code RateLimitFilter} logic lives in
 * {@code RateLimitFilterTest}; this verifies the filter actually runs in the
 * pipeline and emits {@code X-RateLimit-*} / {@code Retry-After} headers.
 */
@QuarkusTest
@TestProfile(RateLimitIntegrationTest.RateLimitEnabledProfile.class)
@DisplayName("Rate Limit Integration Tests")
class RateLimitIntegrationTest {

    private static final int BURST_CAPACITY = 3;

    public static class RateLimitEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            var overrides = new HashMap<String, String>();
            overrides.put("aussie.rate-limiting.enabled", "true");
            overrides.put("aussie.rate-limiting.redis.enabled", "false");
            overrides.put("aussie.rate-limiting.algorithm", "BUCKET");
            overrides.put("aussie.rate-limiting.default-requests-per-window", String.valueOf(BURST_CAPACITY));
            overrides.put("aussie.rate-limiting.window-seconds", "60");
            overrides.put("aussie.rate-limiting.burst-capacity", String.valueOf(BURST_CAPACITY));
            overrides.put("aussie.rate-limiting.include-headers", "true");
            return overrides;
        }
    }

    @Test
    @DisplayName("allowed request should include X-RateLimit-* headers")
    void allowedRequestShouldExposeRateLimitHeaders() {
        given().header("X-API-Key-ID", "headers-only-client")
                .when()
                .get("/admin/services")
                .then()
                .statusCode(200)
                .header("X-RateLimit-Limit", org.hamcrest.Matchers.notNullValue())
                .header("X-RateLimit-Remaining", org.hamcrest.Matchers.notNullValue())
                .header("X-RateLimit-Reset", org.hamcrest.Matchers.notNullValue());
        // The above call consumes one token from the headers-only-client bucket;
        // it does not impact the burst-test client below since we use a different key.
    }

    @Test
    @DisplayName("burst beyond capacity should yield 429 with Retry-After")
    void burstShouldEventuallyReturn429() {
        var clientKey = "burst-test-client";

        // Each iteration shares the same bucket via X-API-Key-ID. The first
        // BURST_CAPACITY requests must succeed; the next must be rejected.
        for (int i = 1; i <= BURST_CAPACITY; i++) {
            given().header("X-API-Key-ID", clientKey)
                    .when()
                    .get("/admin/services")
                    .then()
                    .statusCode(200);
        }

        given().header("X-API-Key-ID", clientKey)
                .when()
                .get("/admin/services")
                .then()
                .statusCode(429)
                .header("Retry-After", org.hamcrest.Matchers.notNullValue())
                .header("X-RateLimit-Remaining", "0");
    }
}
