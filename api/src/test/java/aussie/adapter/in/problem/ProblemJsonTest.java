package aussie.adapter.in.problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ProblemJson")
class ProblemJsonTest {

    @Nested
    @DisplayName("RFC 9457 field shape")
    class FieldShape {

        @Test
        @DisplayName("emits stable type, code, status, title, and detail")
        void emitsBaseFields() {
            var problem = new ProblemDetail("Not Found", 404, "missing");

            var serialized = ProblemJson.serialize(problem);
            var json = new JsonObject(serialized);

            assertEquals("urn:aussie:problem:not_found", json.getString("type"));
            assertEquals("not_found", json.getString("code"));
            assertEquals("Not Found", json.getString("title"));
            assertEquals(404, json.getInteger("status"));
            assertEquals("missing", json.getString("detail"));
        }

        @Test
        @DisplayName("omits null detail and preserves empty detail (matches Jackson serializer)")
        void detailField() {
            var nullDetail = ProblemJson.serialize(new ProblemDetail("X", 502, null));
            var emptyDetail = ProblemJson.serialize(new ProblemDetail("X", 502, ""));

            assertFalse(new JsonObject(nullDetail).containsKey("detail"), nullDetail);
            assertEquals("", new JsonObject(emptyDetail).getString("detail"), emptyDetail);
        }

        @Test
        @DisplayName("status is encoded as a JSON number, not a string")
        void statusIsNumeric() {
            var serialized = ProblemJson.serialize(new ProblemDetail("X", 502, "bad"));
            assertTrue(serialized.contains("\"status\":502"), serialized);
        }

        @Test
        @DisplayName("includes instance when provided, omits it when null or empty")
        void instanceField() {
            var problem = new ProblemDetail("Not Found", 404, "missing");
            var withInstance = ProblemJson.serialize(problem, "/api/things/42");
            assertEquals("/api/things/42", new JsonObject(withInstance).getString("instance"));

            var nullInstance = ProblemJson.serialize(problem, null);
            assertFalse(new JsonObject(nullInstance).containsKey("instance"));

            var emptyInstance = ProblemJson.serialize(problem, "");
            assertFalse(new JsonObject(emptyInstance).containsKey("instance"));
        }
    }

    @Nested
    @DisplayName("Extras")
    class Extras {

        @Test
        @DisplayName("includes extras as top-level fields in insertion order")
        void extrasAtTopLevelInOrder() {
            var extras = new LinkedHashMap<String, Object>();
            extras.put("retryAfter", 30L);
            extras.put("limit", 100L);
            extras.put("remaining", 0L);
            extras.put("resetAt", 1234567890L);

            var serialized = ProblemJson.serialize(new ProblemDetail("Too Many Requests", 429, "calm down", extras));
            var json = new JsonObject(serialized);

            assertEquals(30L, json.getLong("retryAfter"));
            assertEquals(100L, json.getLong("limit"));
            assertEquals(0L, json.getLong("remaining"));
            assertEquals(1234567890L, json.getLong("resetAt"));
            assertTrue(
                    serialized.indexOf("\"code\"") < serialized.indexOf("\"retryAfter\"")
                            && serialized.indexOf("\"retryAfter\"") < serialized.indexOf("\"limit\"")
                            && serialized.indexOf("\"limit\"") < serialized.indexOf("\"remaining\"")
                            && serialized.indexOf("\"remaining\"") < serialized.indexOf("\"resetAt\""),
                    serialized);
        }
    }

    @Nested
    @DisplayName("Content type")
    class ContentType {

        @Test
        @DisplayName("CONTENT_TYPE is application/problem+json")
        void contentTypeIsProblemJson() {
            assertEquals("application/problem+json", ProblemJson.CONTENT_TYPE);
        }
    }
}
