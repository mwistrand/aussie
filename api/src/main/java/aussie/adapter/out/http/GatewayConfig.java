package aussie.adapter.out.http;

import io.smallrye.config.ConfigMapping;

import aussie.core.config.LimitsConfig;
import aussie.core.config.TrustedProxyConfig;
import aussie.core.model.auth.AccessControlConfig;

@ConfigMapping(prefix = "aussie.gateway")
public interface GatewayConfig {

    ForwardingConfig forwarding();

    LimitsConfig limits();

    AccessControlConfig accessControl();

    SecurityConfig security();

    TrustedProxyConfig trustedProxy();
}
