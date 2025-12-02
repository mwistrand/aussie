package aussie.forwarding;

import aussie.config.GatewayConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class ForwardedHeaderBuilderFactory {

    private final GatewayConfig config;
    private final Rfc7239ForwardedHeaderBuilder rfc7239Builder;
    private final XForwardedHeaderBuilder xForwardedBuilder;

    @Inject
    public ForwardedHeaderBuilderFactory(
            GatewayConfig config,
            Rfc7239ForwardedHeaderBuilder rfc7239Builder,
            XForwardedHeaderBuilder xForwardedBuilder) {
        this.config = config;
        this.rfc7239Builder = rfc7239Builder;
        this.xForwardedBuilder = xForwardedBuilder;
    }

    public ForwardedHeaderBuilder getBuilder() {
        if (config.forwarding().useRfc7239()) {
            return rfc7239Builder;
        }
        return xForwardedBuilder;
    }
}
