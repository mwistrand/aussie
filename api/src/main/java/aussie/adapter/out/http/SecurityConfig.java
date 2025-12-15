package aussie.adapter.out.http;

import io.smallrye.config.WithDefault;

import aussie.core.model.auth.GatewaySecurityConfig;

/**
 * Security configuration for the gateway.
 * Implements the core layer's GatewaySecurityConfig interface.
 */
public interface SecurityConfig extends GatewaySecurityConfig {

    /**
     * When false (default), services cannot set defaultVisibility to PUBLIC.
     * When true, services may set defaultVisibility to PUBLIC.
     */
    @WithDefault("false")
    @Override
    boolean publicDefaultVisibilityEnabled();
}
