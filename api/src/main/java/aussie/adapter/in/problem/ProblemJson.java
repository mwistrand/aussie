package aussie.adapter.in.problem;

import io.vertx.core.json.JsonObject;

/**
 * RFC 9457 JSON serializer for {@link ProblemDetail}. Used by the native
 * Vert.x error-write path; the JAX-RS path goes through
 * {@code quarkus-resteasy-problem}'s own Jackson serializer (which conditionally
 * omits {@code type} when not set).
 *
 * <p>Wire shape: {@code type, status, title, detail?, instance?, code, extras...}.
 * {@code detail} is omitted when null; {@code instance} is omitted
 * when null. The JAX-RS path always emits {@code instance} because
 * {@code ProblemDefaultsProvider} backfills it from the request path; callers
 * on the Vert.x path should pass the request path for parity with that body.
 */
public final class ProblemJson {

    public static final String CONTENT_TYPE = "application/problem+json";

    private ProblemJson() {}

    public static String serialize(ProblemDetail problem) {
        return serialize(problem, null);
    }

    public static String serialize(ProblemDetail problem, String instance) {
        final var json = new JsonObject()
                .put("type", problem.type())
                .put("status", problem.status())
                .put("title", problem.title());
        final var detail = problem.detail();
        if (detail != null) {
            json.put("detail", detail);
        }
        if (instance != null && !instance.isEmpty()) {
            json.put("instance", instance);
        }
        json.put("code", problem.code());
        problem.extras().forEach(json::put);
        return json.encode();
    }
}
