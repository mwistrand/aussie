package aussie.config;

import java.time.Duration;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration for admin bootstrap functionality.
 *
 * <p>Bootstrap mode allows creating an initial admin API key when no admin keys
 * exist in the system, solving the chicken-and-egg problem of needing an admin
 * key to create the first admin key.
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>{@code aussie.bootstrap.enabled} - Enable bootstrap mode (default: false)</li>
 *   <li>{@code aussie.bootstrap.key} - Required bootstrap key (min 32 chars)</li>
 *   <li>{@code aussie.bootstrap.ttl} - Bootstrap key TTL (default: PT24H, max: PT24H)</li>
 *   <li>{@code aussie.bootstrap.recovery-mode} - Allow bootstrap with existing keys (default: false)</li>
 * </ul>
 *
 * <h2>Environment Variables</h2>
 * <ul>
 *   <li>{@code AUSSIE_BOOTSTRAP_ENABLED} - Enable bootstrap mode</li>
 *   <li>{@code AUSSIE_BOOTSTRAP_KEY} - Required bootstrap key</li>
 *   <li>{@code AUSSIE_BOOTSTRAP_TTL} - Bootstrap key TTL</li>
 *   <li>{@code AUSSIE_BOOTSTRAP_RECOVERY_MODE} - Enable recovery mode</li>
 * </ul>
 */
@ConfigMapping(prefix = "aussie.bootstrap")
public interface BootstrapConfig {

    /**
     * Whether bootstrap mode is enabled.
     *
     * <p>When enabled, the system will create a bootstrap admin key on startup
     * using the key provided via {@link #key()}.
     *
     * @return true if bootstrap mode is enabled
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * The bootstrap key provided by the administrator.
     *
     * <p>This key is required when bootstrap is enabled. It must be at least
     * 32 characters long for security. The key will be hashed and stored;
     * the plaintext is never logged or persisted.
     *
     * @return the bootstrap key, or empty if not configured
     */
    Optional<String> key();

    /**
     * Time-to-live for the bootstrap key.
     *
     * <p>Bootstrap keys are forced to have a short TTL to ensure operators
     * transition to properly managed keys. Maximum allowed is 24 hours.
     * Any value exceeding 24 hours will be capped.
     *
     * @return the bootstrap key TTL (default: 24 hours)
     */
    @WithDefault("PT24H")
    Duration ttl();

    /**
     * Recovery mode allows bootstrap even when admin keys already exist.
     *
     * <p>This is a dangerous setting intended for emergency recovery when
     * all admin keys have been lost or compromised. Use with caution.
     *
     * <p>In non-recovery mode (default), bootstrap only runs if no admin
     * keys exist in the system.
     *
     * @return true if recovery mode is enabled
     */
    @WithDefault("false")
    boolean recoveryMode();
}
