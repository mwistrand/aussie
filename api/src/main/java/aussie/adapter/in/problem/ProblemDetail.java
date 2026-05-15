package aussie.adapter.in.problem;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Data carrier for an RFC 9457 problem document. Single source of truth for
 * the title / status / detail / extras tuple so the JAX-RS path (via
 * {@link GatewayProblem}) and the native Vert.x error path (via
 * {@code ProxyErrorWriter} + {@link ProblemJson}) emit identical bodies.
 *
 * <p>Contract: {@code title} is required; {@code detail} may be null (omitted
 * from the wire body in that case); {@code extras} preserve insertion order
 * on the wire to match the Jackson serializer used by the JAX-RS path, and
 * may not use keys reserved by RFC 9457 §3 ({@code type}, {@code title},
 * {@code status}, {@code detail}, {@code instance}).
 */
public record ProblemDetail(String title, int status, String detail, Map<String, Object> extras) {

    private static final Set<String> RESERVED_KEYS = Set.of("type", "title", "status", "detail", "instance");

    public ProblemDetail {
        if (title == null) {
            throw new IllegalArgumentException("title is required");
        }
        if (extras == null || extras.isEmpty()) {
            extras = Map.of();
        } else {
            for (final var key : extras.keySet()) {
                if (RESERVED_KEYS.contains(key)) {
                    throw new IllegalArgumentException("extras key '" + key + "' collides with an RFC 9457 base field");
                }
            }
            extras = Collections.unmodifiableMap(new LinkedHashMap<>(extras));
        }
    }

    public ProblemDetail(String title, int status, String detail) {
        this(title, status, detail, Map.of());
    }

    // ========== Not Found ==========

    public static ProblemDetail serviceNotFound(String serviceId) {
        return new ProblemDetail("Service Not Found", 404, "Service '%s' is not registered".formatted(serviceId));
    }

    public static ProblemDetail routeNotFound(String path) {
        return new ProblemDetail("Route Not Found", 404, "No route matches path '%s'".formatted(path));
    }

    public static ProblemDetail resourceNotFound(String resourceType, String resourceId) {
        return new ProblemDetail(
                "%s Not Found".formatted(resourceType), 404, "%s not found: %s".formatted(resourceType, resourceId));
    }

    public static ProblemDetail notFound(String detail) {
        return new ProblemDetail("Not Found", 404, detail);
    }

    // ========== Bad Request ==========

    public static ProblemDetail badRequest(String detail) {
        return new ProblemDetail("Bad Request", 400, detail);
    }

    public static ProblemDetail validationError(String detail) {
        return new ProblemDetail("Validation Error", 400, detail);
    }

    // ========== Auth ==========

    public static ProblemDetail unauthorized(String detail) {
        return new ProblemDetail("Unauthorized", 401, detail);
    }

    public static ProblemDetail forbidden(String detail) {
        return new ProblemDetail("Forbidden", 403, detail);
    }

    // ========== Gateway ==========

    public static ProblemDetail badGateway(String detail) {
        return new ProblemDetail("Bad Gateway", 502, detail);
    }

    // ========== Rate Limit ==========

    public static ProblemDetail tooManyRequests(
            String detail, long retryAfterSeconds, long limit, long remaining, long resetAt) {
        final var extras = new LinkedHashMap<String, Object>(4);
        extras.put("retryAfter", retryAfterSeconds);
        extras.put("limit", limit);
        extras.put("remaining", remaining);
        extras.put("resetAt", resetAt);
        return new ProblemDetail("Too Many Requests", 429, detail, extras);
    }

    public static ProblemDetail tooManyRequests(String detail, long retryAfterSeconds) {
        return new ProblemDetail("Too Many Requests", 429, detail, Map.of("retryAfter", retryAfterSeconds));
    }

    // ========== Size ==========

    public static ProblemDetail payloadTooLarge(String detail) {
        return new ProblemDetail("Payload Too Large", 413, detail);
    }

    public static ProblemDetail headerTooLarge(String detail) {
        return new ProblemDetail("Request Header Fields Too Large", 431, detail);
    }

    // ========== Conflict ==========

    public static ProblemDetail conflict(String detail) {
        return new ProblemDetail("Conflict", 409, detail);
    }

    // ========== Server ==========

    public static ProblemDetail internalError(String detail) {
        return new ProblemDetail("Internal Server Error", 500, detail);
    }

    public static ProblemDetail serviceUnavailable(String detail) {
        return new ProblemDetail("Service Unavailable", 503, detail);
    }

    // Returns 404 (not 503) so the existence of the feature is not advertised to clients.
    public static ProblemDetail featureDisabled(String feature) {
        return new ProblemDetail("Feature Disabled", 404, "%s is disabled".formatted(feature));
    }
}
