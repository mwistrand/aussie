package aussie.adapter.out.http;

import io.smallrye.config.ConfigMapping;

import aussie.core.service.AccessControlConfig;
import aussie.core.service.LimitsConfig;

@ConfigMapping(prefix = "aussie.gateway")
public interface GatewayConfig {

    ForwardingConfig forwarding();

    LimitsConfig limits();

    AccessControlConfig accessControl();
}
