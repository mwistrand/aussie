package aussie.core.service.common;

import java.time.Duration;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.BootstrapConfig;
import aussie.core.model.auth.ApiKey;
import aussie.core.model.auth.Permission;
import aussie.core.model.common.BootstrapResult;
import aussie.core.port.in.ApiKeyManagement;
import aussie.core.port.in.BootstrapManagement;
import aussie.core.port.out.ApiKeyRepository;

/**
 * Service for managing bootstrap admin key creation.
 *
 * <p>
 * Implements the bootstrap flow for creating the first admin API key
 * in a new deployment or recovering access to a locked-out system.
 *
 * <p>
 * The bootstrap key must be provided by the administrator via the
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
    public Uni<BootstrapResult> bootstrap() {
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

        // Enforce maximum TTL
        Duration ttl = enforceTtlLimit(config.ttl());

        // Check if this is a recovery scenario and create the bootstrap key
        return hasAdminKeys().flatMap(hasKeys -> {
            boolean isRecovery = config.recoveryMode() && hasKeys;
            return apiKeyService
                    .createWithKey(
                            BOOTSTRAP_KEY_NAME,
                            BOOTSTRAP_KEY_DESCRIPTION,
                            Set.of(Permission.ALL), // Wildcard permission for full access
                            ttl,
                            plaintextKey,
                            "bootstrap")
                    .map(createResult -> {
                        LOG.infof(
                                "Bootstrap API key created: id=%s, expires=%s",
                                createResult.keyId(), createResult.metadata().expiresAt());

                        return isRecovery
                                ? BootstrapResult.recovery(
                                        createResult.keyId(),
                                        createResult.metadata().expiresAt())
                                : BootstrapResult.standard(
                                        createResult.keyId(),
                                        createResult.metadata().expiresAt());
                    });
        });
    }

    @Override
    public Uni<Boolean> hasAdminKeys() {
        return repository
                .findAll()
                .map(keys -> keys.stream().filter(ApiKey::isValid).anyMatch(this::isAdminKey));
    }

    @Override
    public Uni<Boolean> shouldBootstrap() {
        if (!config.enabled()) {
            return Uni.createFrom().item(false);
        }

        if (config.recoveryMode()) {
            return Uni.createFrom().item(true); // Recovery mode always allows bootstrap
        }

        return hasAdminKeys().map(hasKeys -> !hasKeys);
    }

    /**
     * Check if a key has admin-level permissions.
     */
    private boolean isAdminKey(ApiKey key) {
        return key.permissions().contains(Permission.ALL)
                || key.permissions().contains(Permission.SERVICE_CONFIG_CREATE)
                || key.permissions().contains(Permission.SERVICE_CONFIG_UPDATE)
                || key.permissions().contains(Permission.SERVICE_CONFIG_DELETE);
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
     * Format a duration for human-readable display.
     */
    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        if (hours > 0) {
            return hours + " hours";
        }
        return duration.toMinutes() + " minutes";
    }
}
