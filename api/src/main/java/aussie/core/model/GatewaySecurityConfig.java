package aussie.core.model;

/**
 * Port interface for gateway security configuration.
 * This allows the core layer to access security settings without depending on adapter implementations.
 */
public interface GatewaySecurityConfig {

    /**
     * Returns whether services are allowed to set defaultVisibility to PUBLIC.
     * When false (the default), services must use PRIVATE as their default visibility.
     */
    boolean publicDefaultVisibilityEnabled();
}
