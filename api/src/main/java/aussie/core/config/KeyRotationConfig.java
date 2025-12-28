package aussie.core.config;

import java.time.Duration;
import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Configuration for signing key rotation.
 *
 * <p>Example configuration:
 * <pre>{@code
 * aussie.auth.key-rotation.enabled=true
 * aussie.auth.key-rotation.schedule=0 0 0 1 * /3 ?
 * aussie.auth.key-rotation.cache-refresh-interval=PT5M
 * aussie.auth.key-rotation.cleanup-interval=PT1H
 * aussie.auth.key-rotation.grace-period=PT24H
 * aussie.auth.key-rotation.deprecation-period=P7D
 * aussie.auth.key-rotation.key-size=2048
 * }</pre>
 */
@ConfigMapping(prefix = "aussie.auth.key-rotation")
public interface KeyRotationConfig {

    /**
     * Whether key rotation is enabled.
     *
     * <p>When disabled, the static key from route-auth configuration is used.
     */
    @WithDefault("false")
    boolean enabled();

    /**
     * Cron schedule for automatic key rotation.
     *
     * <p>Default: Quarterly rotation (first day of every third month at midnight).
     * Format follows Quartz cron syntax.
     */
    @WithDefault("0 0 0 1 */3 ?")
    String schedule();

    /**
     * How often to refresh the in-memory key cache from the repository.
     *
     * <p>Default: 5 minutes. Lower values provide faster propagation of
     * key changes across instances but increase repository load.
     */
    @WithName("cache-refresh-interval")
    @WithDefault("PT5M")
    Duration cacheRefreshInterval();

    /**
     * How often to run the retired key cleanup job.
     *
     * <p>Default: 1 hour. This job removes keys that have been retired
     * for longer than the retention period.
     */
    @WithName("cleanup-interval")
    @WithDefault("PT1H")
    Duration cleanupInterval();

    /**
     * Grace period before a new key becomes active.
     *
     * <p>Default: 24 hours. This allows the new key to propagate to all
     * instances before it starts being used for signing.
     */
    @WithName("grace-period")
    @WithDefault("PT24H")
    Duration gracePeriod();

    /**
     * How long deprecated keys remain valid for verification.
     *
     * <p>Default: 7 days. This ensures tokens signed with the old key
     * remain valid during the transition period.
     */
    @WithName("deprecation-period")
    @WithDefault("P7D")
    Duration deprecationPeriod();

    /**
     * How long to retain retired keys before deletion.
     *
     * <p>Default: 30 days. This provides a buffer in case tokens with
     * very long TTLs exist.
     */
    @WithName("retention-period")
    @WithDefault("P30D")
    Duration retentionPeriod();

    /**
     * RSA key size in bits.
     *
     * <p>Default: 2048. Higher values (e.g., 4096) provide more security
     * but slower signing/verification.
     */
    @WithName("key-size")
    @WithDefault("2048")
    int keySize();

    /**
     * Key storage backend type.
     *
     * <p>Options: config, vault, database.
     * Default uses configuration-based key management.
     */
    @WithDefault("config")
    String storage();

    /**
     * Vault-specific configuration (when storage=vault).
     */
    VaultConfig vault();

    interface VaultConfig {
        /**
         * Path in Vault where signing keys are stored.
         */
        @WithDefault("secret/aussie/signing-keys")
        String path();

        /**
         * Optional namespace for Vault Enterprise.
         */
        Optional<String> namespace();
    }
}
