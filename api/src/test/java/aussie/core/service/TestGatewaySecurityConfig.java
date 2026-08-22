package aussie.core.service;

import java.util.List;
import java.util.Optional;

import aussie.core.model.auth.GatewaySecurityConfig;

record TestGatewaySecurityConfig(
        boolean publicDefaultVisibilityEnabled, boolean allowPrivateUpstreams, List<String> upstreamHosts)
        implements GatewaySecurityConfig {

    @Override
    public Optional<List<String>> allowedUpstreamHosts() {
        return Optional.ofNullable(upstreamHosts);
    }

    static GatewaySecurityConfig permissive() {
        return new TestGatewaySecurityConfig(
                true, true, List.of("192.0.2.10", "backend", "example.com", "*.example.com"));
    }

    static GatewaySecurityConfig withPublicVisibility(boolean enabled) {
        return new TestGatewaySecurityConfig(enabled, true, List.of("192.0.2.10"));
    }

    static GatewaySecurityConfig withAllowedUpstreamHosts(List<String> upstreamHosts) {
        return new TestGatewaySecurityConfig(true, true, upstreamHosts);
    }

    static GatewaySecurityConfig withUpstreamPolicy(boolean allowPrivateUpstreams, List<String> upstreamHosts) {
        return new TestGatewaySecurityConfig(true, allowPrivateUpstreams, upstreamHosts);
    }
}
