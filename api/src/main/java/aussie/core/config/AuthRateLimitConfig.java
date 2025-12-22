package aussie.core.config;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration mapping for authentication rate limiting (brute force protection).
 *
 * <p>Configuration prefix: {@code aussie.auth.rate-limit}
 *
 * <p>Authentication rate limiting protects against:
 * <ul>
 *   <li>Credential stuffing attacks</li>
 *   <li>API key brute force attempts</li>
 *   <li>Session enumeration attacks</li>
 * </ul>
 *
 * <p>Failed attempts are tracked by both IP address and identifier (username,
 * email, or API key prefix) to prevent distributed attacks while minimizing
 * false positives for legitimate users behind shared IPs.
 *
 * @see aussie.core.service.auth.AuthRateLimitService
 * @see aussie.spi.FailedAttemptRepository
 */
@ConfigMapping(prefix = "aussie.auth.rate-limit")
public interface AuthRateLimitConfig {

    /**
     * Enable authentication rate limiting.
     *
     * @return true if enabled (default: true)
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Maximum failed authentication attempts before lockout.
     *
     * <p>This applies per tracking key (IP or identifier). When either
     * reaches this threshold, the corresponding key is locked out.
     *
     * @return max attempts (default: 5)
     */
    @WithDefault("5")
    int maxFailedAttempts();

    /**
     * Duration of lockout after max failed attempts.
     *
     * <p>During lockout, all authentication attempts from the locked key
     * are rejected with 429 Too Many Requests.
     *
     * @return lockout duration (default: 15 minutes)
     */
    @WithDefault("PT15M")
    Duration lockoutDuration();

    /**
     * Time window for tracking failed attempts.
     *
     * <p>Failed attempts older than this window are automatically forgotten.
     * This allows recovery without explicit lockout expiration.
     *
     * @return window duration (default: 1 hour)
     */
    @WithDefault("PT1H")
    Duration failedAttemptWindow();

    /**
     * Track failed attempts by client IP address.
     *
     * <p>When enabled, failed attempts are counted per IP, protecting
     * against single-source brute force attacks.
     *
     * @return true if IP tracking is enabled (default: true)
     */
    @WithDefault("true")
    boolean trackByIp();

    /**
     * Track failed attempts by identifier (username, email, or API key prefix).
     *
     * <p>When enabled, failed attempts are counted per identifier, protecting
     * against distributed attacks targeting specific accounts.
     *
     * @return true if identifier tracking is enabled (default: true)
     */
    @WithDefault("true")
    boolean trackByIdentifier();

    /**
     * Multiplier for progressive lockout duration.
     *
     * <p>When a key is locked out multiple times, the lockout duration
     * increases by this multiplier. Set to 1.0 to disable progressive lockout.
     *
     * <p>Example with multiplier 2.0:
     * <ul>
     *   <li>First lockout: 15 minutes</li>
     *   <li>Second lockout: 30 minutes</li>
     *   <li>Third lockout: 60 minutes</li>
     * </ul>
     *
     * @return multiplier (default: 1.5)
     */
    @WithDefault("1.5")
    double progressiveLockoutMultiplier();

    /**
     * Maximum lockout duration for progressive lockout.
     *
     * <p>The lockout duration will not exceed this value regardless
     * of how many times the key has been locked out.
     *
     * @return max duration (default: 24 hours)
     */
    @WithDefault("PT24H")
    Duration maxLockoutDuration();

    /**
     * Include rate limit headers in authentication error responses.
     *
     * <p>When enabled, 401/429 responses include headers showing
     * remaining attempts and retry timing.
     *
     * @return true if headers should be included (default: true)
     */
    @WithDefault("true")
    boolean includeHeaders();
}
