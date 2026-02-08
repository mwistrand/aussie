package aussie.adapter.out.http;

import io.smallrye.config.ConfigMapping;

import aussie.core.model.auth.AccessControlConfig;
import aussie.core.model.common.LimitsConfig;
import aussie.core.model.common.TrustedProxyConfig;

@ConfigMapping(prefix = "aussie.gateway")
public interface GatewayConfig {

    ForwardingConfig forwarding();

    LimitsConfig limits();

    AccessControlConfig accessControl();

    SecurityConfig security();

    TrustedProxyConfig trustedProxy();
}
