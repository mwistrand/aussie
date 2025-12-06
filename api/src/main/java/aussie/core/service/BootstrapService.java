package aussie.core.service;

import java.time.Duration;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import aussie.config.BootstrapConfig;
import aussie.core.model.ApiKey;
import aussie.core.model.BootstrapResult;
import aussie.core.model.Permissions;
import aussie.core.port.in.ApiKeyManagement;
import aussie.core.port.in.BootstrapManagement;
import aussie.core.port.out.ApiKeyRepository;

/**
 * Service for managing bootstrap admin key creation.
 *
 * <p>Implements the bootstrap flow for creating the first admin API key
 * in a new deployment or recovering access to a locked-out system.
 *
 * <p>The bootstrap key must be provided by the administrator via the
 * {@code AUSSIE_BOOTSTRAP_KEY} environment variable. Keys are never
 * auto-generated to ensure the administrator has control over the
 * initial credential.
 */
@ApplicationScoped
public class BootstrapService implements BootstrapManagement {

    private static final Logger LOG = Logger.getLogger(BootstrapService.class);

    private static final String BOOTSTRAP_KEY_NAME = "bootstrap-admin";
    private static final String BOOTSTRAP_KEY_DESCRIPTION = "Bootstrap admin key (auto-expires)";
    private static final Duration MAX_BOOTSTRAP_TTL = Duration.ofHours(24);
    private static final int MIN_KEY_LENGTH = 32;

    private final ApiKeyRepository repository;
    private final BootstrapConfig config;
    private final ApiKeyManagement apiKeyService;

    @Inject
    public BootstrapService(ApiKeyRepository repository, BootstrapConfig config, ApiKeyManagement apiKeyService) {
        this.repository = repository;
        this.config = config;
        this.apiKeyService = apiKeyService;
    }

    @Override
    public BootstrapResult bootstrap() {
        // Validate key is provided
        String plaintextKey = config.key()
                .filter(k -> !k.isBlank())
                .orElseThrow(() -> new BootstrapException("Bootstrap key is required when bootstrap is enabled. "
                        + "Set AUSSIE_BOOTSTRAP_KEY environment variable."));

        // Validate key length
        if (plaintextKey.length() < MIN_KEY_LENGTH) {
            throw new BootstrapException("Bootstrap key must be at least " + MIN_KEY_LENGTH + " characters. "
                    + "Received: " + plaintextKey.length() + " characters.");
        }

        boolean isRecovery = config.recoveryMode() && hasAdminKeys();

        // Enforce maximum TTL
        Duration ttl = enforceTtlLimit(config.ttl());

        // Create the bootstrap key
        var createResult = apiKeyService.createWithKey(
                BOOTSTRAP_KEY_NAME, BOOTSTRAP_KEY_DESCRIPTION, Set.of(Permissions.ALL), ttl, plaintextKey, "bootstrap");

        LOG.infof(
                "Bootstrap API key created: id=%s, expires=%s",
                createResult.keyId(), createResult.metadata().expiresAt());

        return isRecovery
                ? BootstrapResult.recovery(
                        createResult.keyId(), createResult.metadata().expiresAt())
                : BootstrapResult.standard(
                        createResult.keyId(), createResult.metadata().expiresAt());
    }

    @Override
    public boolean hasAdminKeys() {
        return repository.findAll().await().indefinitely().stream()
                .filter(ApiKey::isValid)
                .anyMatch(this::isAdminKey);
    }

    @Override
    public boolean shouldBootstrap() {
        if (!config.enabled()) {
            return false;
        }

        if (config.recoveryMode()) {
            return true; // Recovery mode always allows bootstrap
        }

        return !hasAdminKeys();
    }

    /**
     * Check if a key has admin-level permissions.
     */
    private boolean isAdminKey(ApiKey key) {
        return key.permissions().contains(Permissions.ALL) || key.permissions().contains(Permissions.ADMIN_WRITE);
    }

    /**
     * Enforce the maximum TTL limit for bootstrap keys.
     */
    private Duration enforceTtlLimit(Duration requestedTtl) {
        if (requestedTtl == null) {
            LOG.warn("Bootstrap TTL not specified, using default 24 hours");
            return MAX_BOOTSTRAP_TTL;
        }

        if (requestedTtl.isNegative() || requestedTtl.isZero()) {
            throw new BootstrapException("Bootstrap TTL must be positive");
        }

        if (requestedTtl.compareTo(MAX_BOOTSTRAP_TTL) > 0) {
            LOG.warnf(
                    "Bootstrap TTL %s exceeds maximum %s, capping to maximum",
                    formatDuration(requestedTtl), formatDuration(MAX_BOOTSTRAP_TTL));
            return MAX_BOOTSTRAP_TTL;
        }

        return requestedTtl;
    }

    /**
     * Formats a duration for human-readable display.
     */
    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        if (hours > 0) {
            return hours + " hours";
        }
        return duration.toMinutes() + " minutes";
    }
}
