package aussie.core.model.common;

import java.util.List;
import java.util.Optional;

import io.smallrye.config.WithDefault;

/**
 * Configuration for trusted proxy validation.
 *
 * <p>When enabled, forwarding headers ({@code X-Forwarded-For},
 * {@code Forwarded}, {@code X-Real-IP}) are only trusted when the
 * direct connection originates from a listed proxy IP or CIDR range.
 */
public interface TrustedProxyConfig {

    /** @return true if proxy validation is enabled (default: false) */
    @WithDefault("false")
    boolean enabled();

    /** @return list of trusted proxy IPs/CIDRs, e.g. {@code 10.0.0.0/8} */
    Optional<List<String>> proxies();
}
