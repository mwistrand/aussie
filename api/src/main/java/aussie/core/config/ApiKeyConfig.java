package aussie.core.config;

import java.time.Duration;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;

/**
 * Configuration for API key management.
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>{@code aussie.auth.api-keys.max-ttl} - Maximum TTL duration for API keys (optional)</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>
 * aussie.auth.api-keys.max-ttl=P365D   # Max 1 year
 * aussie.auth.api-keys.max-ttl=P90D    # Max 90 days
 * </pre>
 */
@ConfigMapping(prefix = "aussie.auth.api-keys")
public interface ApiKeyConfig {

    /**
     * Maximum TTL duration for API keys.
     *
     * <p>If set, API key creation will fail if the requested TTL exceeds this value.
     * When not set, there is no limit on TTL duration.
     *
     * @return the maximum TTL duration, or empty if not configured
     */
    Optional<Duration> maxTtl();
}
