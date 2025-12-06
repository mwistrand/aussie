package aussie.adapter.in.bootstrap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import aussie.config.BootstrapConfig;
import aussie.core.model.BootstrapResult;
import aussie.core.port.in.BootstrapManagement;
import aussie.core.port.in.BootstrapManagement.BootstrapException;

/**
 * Initializes bootstrap admin key on application startup.
 *
 * <p>Observes the Quarkus StartupEvent and triggers bootstrap key creation
 * if enabled and conditions are met.
 *
 * <h2>Security Considerations</h2>
 * <ul>
 *   <li>The bootstrap key is never logged - only the key ID and expiration</li>
 *   <li>Operators should immediately create a permanent admin key and let bootstrap expire</li>
 *   <li>Bootstrap keys expire within 24 hours by design</li>
 * </ul>
 *
 * <h2>Failure Behavior</h2>
 * <ul>
 *   <li>If bootstrap is enabled but no key is provided: startup FAILS</li>
 *   <li>If the key is too short: startup FAILS</li>
 *   <li>If storage is unavailable: startup FAILS</li>
 * </ul>
 */
@ApplicationScoped
public class BootstrapInitializer {

    private static final Logger LOG = Logger.getLogger(BootstrapInitializer.class);
    private static final Logger AUDIT = Logger.getLogger("aussie.audit.bootstrap");

    private final BootstrapManagement bootstrapService;
    private final BootstrapConfig config;

    @Inject
    public BootstrapInitializer(BootstrapManagement bootstrapService, BootstrapConfig config) {
        this.bootstrapService = bootstrapService;
        this.config = config;
    }

    void onStart(@Observes StartupEvent event) {
        if (!config.enabled()) {
            LOG.debug("Bootstrap mode is disabled");
            return;
        }

        LOG.info("Bootstrap mode is enabled");

        // Validate key is provided before checking anything else
        if (config.key().isEmpty() || config.key().get().isBlank()) {
            LOG.error("========================================");
            LOG.error("BOOTSTRAP FAILED: No key provided");
            LOG.error("Set AUSSIE_BOOTSTRAP_KEY environment variable");
            LOG.error("========================================");
            throw new BootstrapException("Bootstrap is enabled but no key provided. Set AUSSIE_BOOTSTRAP_KEY.");
        }

        if (!bootstrapService.shouldBootstrap()) {
            LOG.info("Bootstrap skipped: admin keys already exist (use recovery mode to override)");
            AUDIT.infof("BOOTSTRAP_SKIPPED reason=admin_keys_exist recovery_mode=%s", config.recoveryMode());
            return;
        }

        LOG.info("Creating bootstrap admin key...");

        try {
            BootstrapResult result = bootstrapService.bootstrap();
            logBootstrapResult(result);
        } catch (BootstrapException e) {
            LOG.error("========================================");
            LOG.errorf("BOOTSTRAP FAILED: %s", e.getMessage());
            LOG.error("========================================");
            throw e; // Re-throw to fail startup
        } catch (Exception e) {
            LOG.error("========================================");
            LOG.errorf(e, "BOOTSTRAP FAILED: Unexpected error: %s", e.getMessage());
            LOG.error("========================================");
            throw new BootstrapException("Bootstrap failed unexpectedly", e);
        }
    }

    private void logBootstrapResult(BootstrapResult result) {
        // Audit log for security compliance
        AUDIT.infof(
                "BOOTSTRAP_KEY_CREATED keyId=%s expiresAt=%s recovery=%s",
                result.keyId(), result.expiresAt(), result.wasRecovery());

        if (result.wasRecovery()) {
            LOG.warn("========================================");
            LOG.warn("RECOVERY MODE: Bootstrap key created despite existing admin keys");
            LOG.warn("This may indicate a security incident. Review immediately.");
            LOG.warn("========================================");
        }

        LOG.info("========================================");
        LOG.info("BOOTSTRAP ADMIN KEY CREATED SUCCESSFULLY");
        LOG.infof("Key ID: %s", result.keyId());
        LOG.infof("Expires: %s", result.expiresAt());
        LOG.info("");
        LOG.info("IMPORTANT:");
        LOG.info("1. Use this key to create a permanent admin key:");
        LOG.info("   POST /admin/api-keys with Authorization: Bearer <your-bootstrap-key>");
        LOG.info("2. Store the new key securely");
        LOG.info("3. The bootstrap key will expire automatically");
        LOG.info("========================================");
    }
}
