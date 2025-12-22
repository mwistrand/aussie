package aussie.adapter.out.telemetry;

/**
 * Constants for span attributes used in distributed tracing.
 *
 * <p>These attributes provide consistent naming across all instrumentation points
 * in the Aussie gateway. They follow OpenTelemetry semantic conventions where applicable.
 *
 * <p>Use {@link TelemetryHelper} to conditionally add these attributes based on
 * {@link TelemetryConfig.AttributesConfig} settings.
 *
 * @see <a href="https://opentelemetry.io/docs/specs/semconv/">OpenTelemetry Semantic Conventions</a>
 * @see TelemetryHelper
 * @see TelemetryConfig.AttributesConfig
 */
public final class SpanAttributes {

    private SpanAttributes() {}

    // -------------------------------------------------------------------------
    // OpenTelemetry Semantic Convention Attributes
    // @see https://opentelemetry.io/docs/specs/semconv/http/http-spans/
    // -------------------------------------------------------------------------

    /** HTTP method (GET, POST, etc.). */
    public static final String HTTP_METHOD = "http.method";

    /** Full URL of the HTTP request. */
    public static final String HTTP_URL = "http.url";

    /** HTTP response status code. */
    public static final String HTTP_STATUS_CODE = "http.status_code";

    /** Remote host name. */
    public static final String NET_PEER_NAME = "net.peer.name";

    /** Remote port number. */
    public static final String NET_PEER_PORT = "net.peer.port";

    // -------------------------------------------------------------------------
    // Service Identification
    // -------------------------------------------------------------------------

    /** The service ID of the target upstream service. */
    public static final String SERVICE_ID = "aussie.service.id";

    /** The human-readable name of the target service. */
    public static final String SERVICE_NAME = "aussie.service.name";

    /** The route pattern that matched the request (e.g., /api/users/{id}). */
    public static final String ROUTE_PATTERN = "aussie.route.pattern";

    /** The actual path requested. */
    public static final String ROUTE_PATH = "aussie.route.path";

    // -------------------------------------------------------------------------
    // Request Attributes
    // -------------------------------------------------------------------------

    /** Size of the incoming request body in bytes. */
    public static final String REQUEST_SIZE = "aussie.request.size";

    /** Size of the response body in bytes. */
    public static final String RESPONSE_SIZE = "aussie.response.size";

    /** The routing mode used (gateway or passthrough). */
    public static final String ROUTING_MODE = "aussie.routing.mode";

    // -------------------------------------------------------------------------
    // Proxy/Upstream Attributes
    // -------------------------------------------------------------------------

    /** Hostname of the upstream service. */
    public static final String UPSTREAM_HOST = "aussie.upstream.host";

    /** Port of the upstream service. */
    public static final String UPSTREAM_PORT = "aussie.upstream.port";

    /** Full URI of the upstream request. */
    public static final String UPSTREAM_URI = "aussie.upstream.uri";

    /** Time in milliseconds to receive response from upstream. */
    public static final String UPSTREAM_LATENCY_MS = "aussie.upstream.latency_ms";

    // -------------------------------------------------------------------------
    // Authentication Attributes
    // -------------------------------------------------------------------------

    /** Authentication method used (api_key, session, jwt, none). */
    public static final String AUTH_METHOD = "aussie.auth.method";

    /** API key ID (not the key itself) for API key authentication. */
    public static final String API_KEY_ID = "aussie.auth.api_key_id";

    /** Whether authentication was required for this request. */
    public static final String AUTH_REQUIRED = "aussie.auth.required";

    // -------------------------------------------------------------------------
    // Traffic Attribution
    // -------------------------------------------------------------------------

    /** Team identifier for cost allocation. */
    public static final String TEAM_ID = "aussie.team.id";

    /** Tenant identifier for multi-tenant deployments. */
    public static final String TENANT_ID = "aussie.tenant.id";

    /** Client application identifier. */
    public static final String CLIENT_APPLICATION = "aussie.client.application";

    // -------------------------------------------------------------------------
    // WebSocket Attributes
    // -------------------------------------------------------------------------

    /** WebSocket session identifier. */
    public static final String WEBSOCKET_SESSION_ID = "aussie.websocket.session_id";

    /** Backend URI for WebSocket proxy. */
    public static final String WEBSOCKET_BACKEND_URI = "aussie.websocket.backend_uri";

    /** Number of messages forwarded in WebSocket session. */
    public static final String WEBSOCKET_MESSAGE_COUNT = "aussie.websocket.message_count";

    // -------------------------------------------------------------------------
    // Gateway Result
    // -------------------------------------------------------------------------

    /** Type of gateway result (success, unauthorized, forbidden, etc.). */
    public static final String GATEWAY_RESULT_TYPE = "aussie.gateway.result_type";

    /** Error message if the gateway result was an error. */
    public static final String GATEWAY_ERROR_MESSAGE = "aussie.gateway.error_message";

    // -------------------------------------------------------------------------
    // Routing Mode Values
    // -------------------------------------------------------------------------

    /** Routing mode value for gateway (route-based) requests. */
    public static final String ROUTING_MODE_GATEWAY = "gateway";

    /** Routing mode value for pass-through (service-based) requests. */
    public static final String ROUTING_MODE_PASSTHROUGH = "passthrough";

    // -------------------------------------------------------------------------
    // Authentication Method Values
    // -------------------------------------------------------------------------

    /** Auth method value for API key authentication. */
    public static final String AUTH_METHOD_API_KEY = "api_key";

    /** Auth method value for session-based authentication. */
    public static final String AUTH_METHOD_SESSION = "session";

    /** Auth method value for JWT authentication. */
    public static final String AUTH_METHOD_JWT = "jwt";

    /** Auth method value when no authentication was performed. */
    public static final String AUTH_METHOD_NONE = "none";

    // -------------------------------------------------------------------------
    // Gateway Result Type Values
    // -------------------------------------------------------------------------

    /** Result type for successful requests. */
    public static final String RESULT_SUCCESS = "success";

    /** Result type for route not found. */
    public static final String RESULT_ROUTE_NOT_FOUND = "route_not_found";

    /** Result type for service not found. */
    public static final String RESULT_SERVICE_NOT_FOUND = "service_not_found";

    /** Result type for reserved path. */
    public static final String RESULT_RESERVED_PATH = "reserved_path";

    /** Result type for upstream errors. */
    public static final String RESULT_ERROR = "error";

    /** Result type for unauthorized requests. */
    public static final String RESULT_UNAUTHORIZED = "unauthorized";

    /** Result type for forbidden requests. */
    public static final String RESULT_FORBIDDEN = "forbidden";

    /** Result type for bad requests. */
    public static final String RESULT_BAD_REQUEST = "bad_request";

    // -------------------------------------------------------------------------
    // Rate Limiting Attributes
    // -------------------------------------------------------------------------

    /** Whether the request was rate limited. */
    public static final String RATE_LIMITED = "aussie.rate_limit.limited";

    /** Rate limit bucket/window limit. */
    public static final String RATE_LIMIT_LIMIT = "aussie.rate_limit.limit";

    /** Remaining requests in current window. */
    public static final String RATE_LIMIT_REMAINING = "aussie.rate_limit.remaining";

    /** Unix timestamp when the rate limit resets. */
    public static final String RATE_LIMIT_RESET = "aussie.rate_limit.reset";

    /** Seconds until the client can retry. */
    public static final String RATE_LIMIT_RETRY_AFTER = "aussie.rate_limit.retry_after";

    /** Type of rate limit applied (http, ws_connection, ws_message). */
    public static final String RATE_LIMIT_TYPE = "aussie.rate_limit.type";

    // -------------------------------------------------------------------------
    // Rate Limit Type Values
    // -------------------------------------------------------------------------

    /** Rate limit type for HTTP requests. */
    public static final String RATE_LIMIT_TYPE_HTTP = "http";

    /** Rate limit type for WebSocket connections. */
    public static final String RATE_LIMIT_TYPE_WS_CONNECTION = "ws_connection";

    /** Rate limit type for WebSocket messages. */
    public static final String RATE_LIMIT_TYPE_WS_MESSAGE = "ws_message";

    // -------------------------------------------------------------------------
    // Authentication Rate Limiting (Brute Force Protection)
    // -------------------------------------------------------------------------

    /** Whether the request was blocked by auth rate limiting (lockout). */
    public static final String AUTH_RATE_LIMITED = "aussie.auth.rate_limited";

    /** The key that triggered the auth lockout (ip:xxx, user:xxx, apikey:xxx). */
    public static final String AUTH_LOCKOUT_KEY = "aussie.auth.lockout_key";

    /** Seconds until the auth lockout expires. */
    public static final String AUTH_LOCKOUT_RETRY_AFTER = "aussie.auth.lockout_retry_after";
}
