package aussie.adapter.out.http;

import io.smallrye.config.ConfigMapping;

import aussie.core.model.AccessControlConfig;
import aussie.core.model.LimitsConfig;

@ConfigMapping(prefix = "aussie.gateway")
public interface GatewayConfig {

    ForwardingConfig forwarding();

    LimitsConfig limits();

    AccessControlConfig accessControl();

    SecurityConfig security();
}
