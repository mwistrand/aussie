package aussie.telemetry;

/**
 * Standard span attribute keys for OpenTelemetry tracing in Aussie Gateway.
 *
 * <p>These constants define consistent attribute names across all spans,
 * enabling correlation and filtering in tracing backends like Jaeger.
 *
 * <p>Attributes follow OpenTelemetry semantic conventions where applicable,
 * with Aussie-specific attributes prefixed with {@code aussie.}.
 *
 * @see <a href="https://opentelemetry.io/docs/specs/semconv/">OpenTelemetry Semantic Conventions</a>
 */
public final class SpanAttributes {

    private SpanAttributes() {
        // Utility class - no instantiation
    }

    // =========================================================================
    // Service Identification
    // =========================================================================

    /**
     * Unique identifier of the target service.
     */
    public static final String SERVICE_ID = "aussie.service.id";

    /**
     * Human-readable name of the target service.
     */
    public static final String SERVICE_NAME = "aussie.service.name";

    /**
     * Unique identifier of the matched route.
     */
    public static final String ROUTE_ID = "aussie.route.id";

    /**
     * Path pattern of the matched route.
     */
    public static final String ROUTE_PATH = "aussie.route.path";

    // =========================================================================
    // Request Attributes
    // =========================================================================

    /**
     * Unique identifier for this request (correlation ID).
     */
    public static final String REQUEST_ID = "aussie.request.id";

    /**
     * Client IP address (may be extracted from X-Forwarded-For).
     */
    public static final String CLIENT_IP = "aussie.client.ip";

    /**
     * Size of the incoming request body in bytes.
     */
    public static final String REQUEST_SIZE = "aussie.request.size";

    /**
     * Size of the outgoing response body in bytes.
     */
    public static final String RESPONSE_SIZE = "aussie.response.size";

    /**
     * HTTP method of the request.
     */
    public static final String HTTP_METHOD = "http.method";

    /**
     * HTTP status code of the response.
     */
    public static final String HTTP_STATUS_CODE = "http.status_code";

    /**
     * Full URL of the request.
     */
    public static final String HTTP_URL = "http.url";

    /**
     * Target path of the request.
     */
    public static final String HTTP_TARGET = "http.target";

    // =========================================================================
    // Proxy/Upstream Attributes
    // =========================================================================

    /**
     * Hostname of the upstream service.
     */
    public static final String UPSTREAM_HOST = "aussie.upstream.host";

    /**
     * Port of the upstream service.
     */
    public static final String UPSTREAM_PORT = "aussie.upstream.port";

    /**
     * Full URL of the upstream request.
     */
    public static final String UPSTREAM_URL = "aussie.upstream.url";

    /**
     * Latency of the upstream request in milliseconds.
     */
    public static final String UPSTREAM_LATENCY_MS = "aussie.upstream.latency_ms";

    /**
     * HTTP status code returned by the upstream service.
     */
    public static final String UPSTREAM_STATUS_CODE = "aussie.upstream.status_code";

    // =========================================================================
    // Authentication Attributes
    // =========================================================================

    /**
     * Authentication method used (api_key, session, oidc, none).
     */
    public static final String AUTH_METHOD = "aussie.auth.method";

    /**
     * Authenticated principal identifier.
     */
    public static final String AUTH_PRINCIPAL = "aussie.auth.principal";

    /**
     * API key identifier (not the key itself).
     */
    public static final String API_KEY_ID = "aussie.auth.api_key_id";

    /**
     * Session identifier (for session-based auth).
     */
    public static final String SESSION_ID = "aussie.auth.session_id";

    /**
     * Authentication result (success, failure, denied).
     */
    public static final String AUTH_RESULT = "aussie.auth.result";

    /**
     * Reason for authentication failure or denial.
     */
    public static final String AUTH_FAILURE_REASON = "aussie.auth.failure_reason";

    // =========================================================================
    // Traffic Attribution
    // =========================================================================

    /**
     * Tenant identifier for multi-tenant deployments.
     */
    public static final String TENANT_ID = "aussie.tenant.id";

    /**
     * Team identifier for cost attribution.
     */
    public static final String TEAM_ID = "aussie.team.id";

    /**
     * Cost center for billing/chargeback.
     */
    public static final String COST_CENTER = "aussie.cost_center";

    /**
     * Environment (dev, staging, prod).
     */
    public static final String ENVIRONMENT = "aussie.environment";

    /**
     * Client application identifier.
     */
    public static final String CLIENT_APPLICATION = "aussie.client.application";

    // =========================================================================
    // WebSocket Attributes
    // =========================================================================

    /**
     * WebSocket connection identifier.
     */
    public static final String WEBSOCKET_CONNECTION_ID = "aussie.websocket.connection_id";

    /**
     * WebSocket message type (text, binary).
     */
    public static final String WEBSOCKET_MESSAGE_TYPE = "aussie.websocket.message_type";

    /**
     * WebSocket message size in bytes.
     */
    public static final String WEBSOCKET_MESSAGE_SIZE = "aussie.websocket.message_size";

    /**
     * WebSocket close code.
     */
    public static final String WEBSOCKET_CLOSE_CODE = "aussie.websocket.close_code";

    // =========================================================================
    // Error Attributes
    // =========================================================================

    /**
     * Error type classification.
     */
    public static final String ERROR_TYPE = "aussie.error.type";

    /**
     * Error message.
     */
    public static final String ERROR_MESSAGE = "aussie.error.message";

    /**
     * Whether the error is retryable.
     */
    public static final String ERROR_RETRYABLE = "aussie.error.retryable";

    // =========================================================================
    // Cache Attributes
    // =========================================================================

    /**
     * Cache operation type (get, set, delete).
     */
    public static final String CACHE_OPERATION = "aussie.cache.operation";

    /**
     * Cache hit or miss.
     */
    public static final String CACHE_HIT = "aussie.cache.hit";

    /**
     * Cache key (may be truncated or hashed for privacy).
     */
    public static final String CACHE_KEY = "aussie.cache.key";

    // =========================================================================
    // Security Attributes
    // =========================================================================

    /**
     * Security event type.
     */
    public static final String SECURITY_EVENT_TYPE = "aussie.security.event_type";

    /**
     * Access control decision (allowed, denied).
     */
    public static final String ACCESS_DECISION = "aussie.security.access_decision";

    /**
     * Reason for access denial.
     */
    public static final String ACCESS_DENIAL_REASON = "aussie.security.denial_reason";
}
