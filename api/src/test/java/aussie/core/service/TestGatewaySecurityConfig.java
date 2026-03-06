package aussie.core.service;

import aussie.core.model.auth.GatewaySecurityConfig;

record TestGatewaySecurityConfig(boolean publicDefaultVisibilityEnabled, boolean allowPrivateUpstreams)
        implements GatewaySecurityConfig {

    static GatewaySecurityConfig permissive() {
        return new TestGatewaySecurityConfig(true, true);
    }

    static GatewaySecurityConfig withPublicVisibility(boolean enabled) {
        return new TestGatewaySecurityConfig(enabled, true);
    }
}
