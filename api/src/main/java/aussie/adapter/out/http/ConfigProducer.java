package aussie.adapter.out.http;

import aussie.core.service.AccessControlConfig;
import aussie.core.service.LimitsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * Produces configuration beans for injection into core services.
 * This bridges the adapter layer's GatewayConfig to the core layer's config interfaces.
 */
@ApplicationScoped
public class ConfigProducer {

    private final GatewayConfig gatewayConfig;

    @Inject
    public ConfigProducer(GatewayConfig gatewayConfig) {
        this.gatewayConfig = gatewayConfig;
    }

    @Produces
    @ApplicationScoped
    public LimitsConfig limitsConfig() {
        return gatewayConfig.limits();
    }

    @Produces
    @ApplicationScoped
    public AccessControlConfig accessControlConfig() {
        return gatewayConfig.accessControl();
    }
}
