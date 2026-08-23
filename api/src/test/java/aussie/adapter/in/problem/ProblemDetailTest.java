package aussie.adapter.in.problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ProblemDetail")
class ProblemDetailTest {

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        @DisplayName("requires a non-null title")
        void titleRequired() {
            assertThrows(IllegalArgumentException.class, () -> new ProblemDetail(null, 404, "x"));
        }

        @Test
        @DisplayName("allows null detail (omitted from the wire body)")
        void nullDetailPreserved() {
            var problem = new ProblemDetail("Title", 404, null);
            assertNull(problem.detail());
        }

        @Test
        @DisplayName("normalizes null extras to an immutable empty map")
        void nullExtrasNormalized() {
            var problem = new ProblemDetail("Title", 404, "x", null);
            assertTrue(problem.extras().isEmpty());
            assertThrows(
                    UnsupportedOperationException.class, () -> problem.extras().put("a", 1));
        }

        @Test
        @DisplayName("defensively copies extras to detach from caller mutation")
        void extrasDefensivelyCopied() {
            var caller = new HashMap<String, Object>();
            caller.put("a", 1);
            var problem = new ProblemDetail("Title", 404, "x", caller);
            caller.put("b", 2);
            assertEquals(1, problem.extras().size());
            assertThrows(
                    UnsupportedOperationException.class, () -> problem.extras().put("c", 3));
        }

        @Test
        @DisplayName("rejects extras keys that collide with RFC 9457 base fields")
        void reservedKeysRejected() {
            for (var reserved : new String[] {"type", "title", "status", "detail", "instance"}) {
                var extras = new HashMap<String, Object>();
                extras.put(reserved, "x");
                assertThrows(IllegalArgumentException.class, () -> new ProblemDetail("Title", 400, "d", extras));
            }
        }
    }

    @Nested
    @DisplayName("Factories")
    class Factories {

        @Test
        @DisplayName("serviceNotFound carries 404 + Service Not Found")
        void serviceNotFound() {
            var problem = ProblemDetail.serviceNotFound("svc");
            assertEquals("Service Not Found", problem.title());
            assertEquals(404, problem.status());
            assertEquals("Service 'svc' is not registered", problem.detail());
        }

        @Test
        @DisplayName("tooManyRequests with full details carries 4 extras")
        void tooManyRequestsFull() {
            var problem = ProblemDetail.tooManyRequests("throttled", 30, 100, 0, 1234);
            assertEquals(429, problem.status());
            assertEquals(30L, problem.extras().get("retryAfter"));
            assertEquals(100L, problem.extras().get("limit"));
            assertEquals(0L, problem.extras().get("remaining"));
            assertEquals(1234L, problem.extras().get("resetAt"));
        }

        @Test
        @DisplayName("tooManyRequests minimal carries 1 extra (retryAfter)")
        void tooManyRequestsMinimal() {
            var problem = ProblemDetail.tooManyRequests("throttled", 30);
            assertEquals(429, problem.status());
            assertEquals(1, problem.extras().size());
            assertEquals(30L, problem.extras().get("retryAfter"));
        }

        @Test
        @DisplayName("badGateway carries 502")
        void badGateway() {
            assertEquals(502, ProblemDetail.badGateway("upstream broke").status());
        }

        @Test
        @DisplayName("gatewayTimeout carries 504")
        void gatewayTimeout() {
            assertEquals(504, ProblemDetail.gatewayTimeout("upstream timed out").status());
        }

        @Test
        @DisplayName("payloadTooLarge carries 413")
        void payloadTooLarge() {
            assertEquals(413, ProblemDetail.payloadTooLarge("big").status());
        }

        @Test
        @DisplayName("headerTooLarge carries 431")
        void headerTooLarge() {
            assertEquals(431, ProblemDetail.headerTooLarge("big").status());
        }
    }
}
