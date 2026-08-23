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
            overrides.put("aussie.gateway.trusted-proxy.enabled", "true");
            overrides.put("aussie.gateway.trusted-proxy.proxies", "127.0.0.1/32,::1/128");
            return overrides;
        }
    }

    @Test
    @DisplayName("allowed request should include X-RateLimit-* headers")
    void allowedRequestShouldExposeRateLimitHeaders() {
        given().header("X-Forwarded-For", "192.0.2.10")
                .when()
                .get("/admin/services")
                .then()
                .statusCode(200)
                .header("X-RateLimit-Limit", org.hamcrest.Matchers.notNullValue())
                .header("X-RateLimit-Remaining", org.hamcrest.Matchers.notNullValue())
                .header("X-RateLimit-Reset", org.hamcrest.Matchers.notNullValue());
        // The trusted network identity keeps this test independent from the burst test.
    }

    @Test
    @DisplayName("burst beyond capacity should yield 429 with Retry-After")
    void burstShouldEventuallyReturn429() {
        // Rotating an unverified API-key identifier must not rotate the canonical
        // network bucket. The first BURST_CAPACITY requests succeed; the next fails.
        for (int i = 1; i <= BURST_CAPACITY; i++) {
            given().header("X-Forwarded-For", "192.0.2.11")
                    .header("X-API-Key-ID", "rotated-key-" + i)
                    .header("Authorization", "Bearer rotated-token-" + i)
                    .cookie("aussie_session", "rotated-session-" + i)
                    .when()
                    .get("/admin/services")
                    .then()
                    .statusCode(200);
        }

        given().header("X-Forwarded-For", "192.0.2.11")
                .header("X-API-Key-ID", "rotated-key-final")
                .header("Authorization", "Bearer rotated-token-final")
                .cookie("aussie_session", "rotated-session-final")
                .when()
                .get("/admin/services")
                .then()
                .statusCode(429)
                .header("Retry-After", org.hamcrest.Matchers.notNullValue())
                .header("X-RateLimit-Remaining", "0");
    }
}
