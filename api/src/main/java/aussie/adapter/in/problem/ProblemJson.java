package aussie.adapter.in.problem;

import io.vertx.core.json.JsonObject;

/**
 * RFC 9457 JSON serializer for {@link ProblemDetail}. Used by the native
 * Vert.x error-write path; the JAX-RS path goes through
 * {@code quarkus-resteasy-problem}'s own Jackson serializer. The two paths
 * emit byte-comparable bodies for the same {@link ProblemDetail}: same field
 * set (no synthetic {@code type:"about:blank"}), same order
 * ({@code status, title, detail, extras}), and {@code detail} omitted when
 * null or empty to match {@code JacksonProblemSerializer}.
 */
public final class ProblemJson {

    public static final String CONTENT_TYPE = "application/problem+json";

    private ProblemJson() {}

    public static String serialize(ProblemDetail problem) {
        final var json = new JsonObject().put("status", problem.status()).put("title", problem.title());
        final var detail = problem.detail();
        if (detail != null && !detail.isEmpty()) {
            json.put("detail", detail);
        }
        problem.extras().forEach(json::put);
        return json.encode();
    }
}
