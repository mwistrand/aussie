package aussie.adapter.out.http;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import aussie.core.port.out.ForwardedHeaderBuilder;
import aussie.core.port.out.ForwardedHeaderBuilderProvider;

@ApplicationScoped
public class ForwardedHeaderBuilderFactory implements ForwardedHeaderBuilderProvider {

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

    @Override
    public ForwardedHeaderBuilder getBuilder() {
        if (config.forwarding().useRfc7239()) {
            return rfc7239Builder;
        }
        return xForwardedBuilder;
    }
}
