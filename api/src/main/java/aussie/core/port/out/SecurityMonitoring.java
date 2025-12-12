package aussie.core.port.out;

/**
 * Port interface for security monitoring and anomaly detection.
 *
 * <p>Implementations track security-related events and detect potential
 * attacks or suspicious behavior patterns.
 */
public interface SecurityMonitoring {

    /**
     * Check if security monitoring is enabled.
     *
     * @return true if enabled
     */
    boolean isEnabled();

    /**
     * Record a request for rate limiting and anomaly detection.
     *
     * @param clientIp the client IP address
     * @param serviceId the target service ID (may be null)
     * @param isError whether the request resulted in an error
     */
    void recordRequest(String clientIp, String serviceId, boolean isError);

    /**
     * Record an authentication failure.
     *
     * @param clientIp the client IP address
     * @param reason the reason for the failure
     * @param method the authentication method attempted
     */
    void recordAuthFailure(String clientIp, String reason, String method);

    /**
     * Record an access denied event.
     *
     * @param clientIp the client IP address
     * @param serviceId the target service ID
     * @param path the requested path
     * @param reason the reason for denial
     */
    void recordAccessDenied(String clientIp, String serviceId, String path, String reason);
}
