package aussie.adapter.out.http;

import java.util.List;
import java.util.Optional;

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

    /**
     * When true, upstream service URLs may use private (site-local) addresses.
     * When false, only publicly routable addresses are accepted.
     */
    @WithDefault("false")
    @Override
    boolean allowPrivateUpstreams();

    /**
     * Exact upstream hosts or explicit {@code *.example.com} subdomain patterns.
     * An absent or empty value denies all upstream registrations.
     */
    @Override
    Optional<List<String>> allowedUpstreamHosts();
}
