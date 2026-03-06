package aussie.core.model.auth;

/**
 * Port interface for gateway security configuration.
 * This allows the core layer to access security settings without depending on adapter implementations.
 */
public interface GatewaySecurityConfig {

    /**
     * Return whether services are allowed to set defaultVisibility to PUBLIC.
     * When false (the default), services must use PRIVATE as their default visibility.
     */
    boolean publicDefaultVisibilityEnabled();

    /**
     * Return whether upstream service URLs may use private (site-local) addresses
     * such as 10.x, 172.16-31.x, and 192.168.x.
     *
     * <p>When true (the default), private addresses are allowed for gateway-to-upstream
     * forwarding. When false, only publicly routable addresses are accepted.
     */
    boolean allowPrivateUpstreams();
}
