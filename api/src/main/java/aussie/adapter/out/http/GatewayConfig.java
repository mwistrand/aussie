package aussie.adapter.out.http;

import aussie.core.service.AccessControlConfig;
import aussie.core.service.LimitsConfig;
import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "aussie.gateway")
public interface GatewayConfig {

    ForwardingConfig forwarding();

    LimitsConfig limits();

    AccessControlConfig accessControl();
}
