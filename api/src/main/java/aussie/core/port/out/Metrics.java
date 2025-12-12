package aussie.core.port.out;

import aussie.core.model.GatewayResult;

/**
 * Port interface for recording gateway metrics.
 *
 * <p>Implementations handle the actual metric recording (e.g., Micrometer, OpenTelemetry).
 */
public interface Metrics {

    /**
     * Check if metrics collection is enabled.
     *
     * @return true if enabled
     */
    boolean isEnabled();

    /**
     * Record a gateway request.
     *
     * @param serviceId the target service ID
     * @param method the HTTP method
     * @param statusCode the response status code
     */
    void recordRequest(String serviceId, String method, int statusCode);

    /**
     * Record proxy latency.
     *
     * @param serviceId the target service ID
     * @param method the HTTP method
     * @param statusCode the response status code
     * @param latencyMs latency in milliseconds
     */
    void recordProxyLatency(String serviceId, String method, int statusCode, long latencyMs);

    /**
     * Record a gateway result.
     *
     * @param serviceId the target service ID
     * @param result the gateway result
     */
    void recordGatewayResult(String serviceId, GatewayResult result);

    /**
     * Record traffic volume.
     *
     * @param serviceId the target service ID
     * @param teamId the team ID (may be null)
     * @param requestBytes incoming bytes
     * @param responseBytes outgoing bytes
     */
    void recordTraffic(String serviceId, String teamId, long requestBytes, long responseBytes);

    /**
     * Record an error.
     *
     * @param serviceId the target service ID
     * @param errorType the type of error
     */
    void recordError(String serviceId, String errorType);

    /**
     * Record an authentication failure.
     *
     * @param reason the reason for the failure
     * @param clientIpHash the hashed client IP
     */
    void recordAuthFailure(String reason, String clientIpHash);

    /**
     * Record a successful authentication.
     *
     * @param method the authentication method
     */
    void recordAuthSuccess(String method);

    /**
     * Record an access denied event.
     *
     * @param serviceId the target service ID
     * @param reason the reason for denial
     */
    void recordAccessDenied(String serviceId, String reason);

    /**
     * Increment active connection count.
     */
    void incrementActiveConnections();

    /**
     * Decrement active connection count.
     */
    void decrementActiveConnections();

    /**
     * Increment active WebSocket count.
     */
    void incrementActiveWebSockets();

    /**
     * Decrement active WebSocket count.
     */
    void decrementActiveWebSockets();

    /**
     * Record a WebSocket connection.
     *
     * @param serviceId the target service ID
     */
    void recordWebSocketConnect(String serviceId);

    /**
     * Record a WebSocket disconnection.
     *
     * @param serviceId the target service ID
     * @param durationMs connection duration in milliseconds
     */
    void recordWebSocketDisconnect(String serviceId, long durationMs);

    /**
     * Record WebSocket connection limit reached.
     */
    void recordWebSocketLimitReached();
}
