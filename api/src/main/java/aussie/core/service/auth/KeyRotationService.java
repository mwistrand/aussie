package aussie.core.service.auth;

import java.time.Instant;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.KeyRotationConfig;
import aussie.core.model.auth.KeyStatus;
import aussie.core.model.auth.SigningKeyRecord;
import aussie.spi.SigningKeyRepository;

/**
 * Service for managing signing key rotation.
 *
 * <p>This service handles:
 * <ul>
 *   <li>Automatic scheduled key rotation</li>
 *   <li>Manual key rotation triggers</li>
 *   <li>Key lifecycle transitions (activate pending keys)</li>
 *   <li>Cleanup of retired keys</li>
 * </ul>
 *
 * <h2>Rotation Process</h2>
 * <ol>
 *   <li>Generate new key pair with PENDING status</li>
 *   <li>After grace period, activate the new key</li>
 *   <li>Current active key transitions to DEPRECATED</li>
 *   <li>After deprecation period, deprecated keys become RETIRED</li>
 *   <li>After retention period, retired keys are deleted</li>
 * </ol>
 */
@ApplicationScoped
public class KeyRotationService {

    private static final Logger LOG = Logger.getLogger(KeyRotationService.class);

    private final SigningKeyRegistry registry;
    private final SigningKeyRepository repository;
    private final KeyRotationConfig config;

    @Inject
    public KeyRotationService(SigningKeyRegistry registry, SigningKeyRepository repository, KeyRotationConfig config) {
        this.registry = registry;
        this.repository = repository;
        this.config = config;
    }

    /**
     * Trigger scheduled key rotation.
     *
     * <p>Generates a new key and transitions the current active key
     * to deprecated status. The new key starts in PENDING status and
     * will be activated after the grace period.
     */
    @Scheduled(
            cron = "${aussie.auth.key-rotation.schedule:0 0 0 1 */3 ?}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public Uni<Void> rotateKeys() {
        if (!config.enabled()) {
            return Uni.createFrom().voidItem();
        }

        LOG.info("Starting scheduled key rotation...");

        return registry.generateAndRegisterKey()
                .flatMap(newKey -> {
                    LOG.infov("New key {0} created, will activate after grace period", newKey.keyId());

                    // Check if we should immediately activate (grace period = 0)
                    if (config.gracePeriod().isZero() || config.gracePeriod().isNegative()) {
                        return registry.activateKey(newKey.keyId()).replaceWith(newKey);
                    }

                    // Otherwise, just register and let the lifecycle job handle activation
                    return Uni.createFrom().item(newKey);
                })
                .invoke(key -> LOG.infov("Key rotation completed: new key {0}", key.keyId()))
                .replaceWithVoid()
                .onFailure()
                .invoke(e -> LOG.error("Key rotation failed", e));
    }

    /**
     * Manually trigger key rotation.
     *
     * <p>Generates and immediately activates a new key. The current
     * active key is deprecated. Use this for emergency rotations.
     *
     * @param reason Optional reason for the rotation (logged)
     * @return Uni with the new active key
     */
    public Uni<SigningKeyRecord> triggerRotation(String reason) {
        if (!config.enabled()) {
            return Uni.createFrom().failure(new IllegalStateException("Key rotation not enabled"));
        }

        LOG.warnv("Manual key rotation triggered: {0}", reason != null ? reason : "no reason provided");

        return registry.generateAndRegisterKey()
                .flatMap(newKey -> registry.activateKey(newKey.keyId())
                        .flatMap(v -> repository.findById(newKey.keyId()))
                        .map(opt -> opt.orElse(newKey)))
                .invoke(key -> LOG.infov("Manual rotation completed: new active key {0}", key.keyId()));
    }

    /**
     * Process key lifecycle transitions.
     *
     * <p>This job:
     * <ul>
     *   <li>Activates PENDING keys past their grace period</li>
     *   <li>Retires DEPRECATED keys past their deprecation period</li>
     * </ul>
     */
    @Scheduled(
            every = "${aussie.auth.key-rotation.cleanup-interval:1h}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public Uni<Void> processKeyLifecycle() {
        if (!config.enabled()) {
            return Uni.createFrom().voidItem();
        }

        LOG.debug("Processing key lifecycle transitions...");

        return Uni.combine()
                .all()
                .unis(activatePendingKeys(), retireDeprecatedKeys(), cleanupRetiredKeys())
                .discardItems()
                .onFailure()
                .invoke(e -> LOG.error("Key lifecycle processing failed", e));
    }

    /**
     * Cleanup retired keys that are past the retention period.
     */
    public Uni<Void> cleanupRetiredKeys() {
        if (!config.enabled()) {
            return Uni.createFrom().voidItem();
        }

        LOG.debug("Cleaning up retired keys...");

        return repository.findByStatus(KeyStatus.RETIRED).flatMap(retiredKeys -> {
            final var retentionPeriod = config.retentionPeriod();
            final var cutoff = Instant.now().minus(retentionPeriod);

            final var toDelete = retiredKeys.stream()
                    .filter(key -> key.retiredAt() != null && key.retiredAt().isBefore(cutoff))
                    .toList();

            if (toDelete.isEmpty()) {
                LOG.debug("No retired keys to delete");
                return Uni.createFrom().voidItem();
            }

            LOG.infov("Deleting {0} retired keys past retention period", toDelete.size());

            return Uni.join()
                    .all(toDelete.stream()
                            .map(key -> repository
                                    .delete(key.keyId())
                                    .invoke(() -> LOG.infov("Deleted retired key: {0}", key.keyId())))
                            .toList())
                    .andFailFast()
                    .replaceWithVoid();
        });
    }

    /**
     * Activate pending keys that are past the grace period.
     */
    private Uni<Void> activatePendingKeys() {
        return repository.findByStatus(KeyStatus.PENDING).flatMap(pendingKeys -> {
            final var gracePeriod = config.gracePeriod();
            final var cutoff = Instant.now().minus(gracePeriod);

            final var toActivate = pendingKeys.stream()
                    .filter(key -> key.createdAt().isBefore(cutoff))
                    .toList();

            if (toActivate.isEmpty()) {
                return Uni.createFrom().voidItem();
            }

            // Activate the most recently created pending key
            final var keyToActivate = toActivate.stream()
                    .max((a, b) -> a.createdAt().compareTo(b.createdAt()))
                    .orElseThrow();

            LOG.infov("Activating pending key {0} (past grace period)", keyToActivate.keyId());

            return registry.activateKey(keyToActivate.keyId());
        });
    }

    /**
     * Retire deprecated keys that are past the deprecation period.
     */
    private Uni<Void> retireDeprecatedKeys() {
        return repository.findByStatus(KeyStatus.DEPRECATED).flatMap(deprecatedKeys -> {
            final var deprecationPeriod = config.deprecationPeriod();
            final var cutoff = Instant.now().minus(deprecationPeriod);

            final var toRetire = deprecatedKeys.stream()
                    .filter(key ->
                            key.deprecatedAt() != null && key.deprecatedAt().isBefore(cutoff))
                    .toList();

            if (toRetire.isEmpty()) {
                return Uni.createFrom().voidItem();
            }

            LOG.infov("Retiring {0} deprecated keys past deprecation period", toRetire.size());

            return Uni.join()
                    .all(toRetire.stream()
                            .map(key -> registry.retireKey(key.keyId()))
                            .toList())
                    .andFailFast()
                    .replaceWithVoid();
        });
    }

    /**
     * Get all keys for administrative purposes.
     */
    public Uni<List<SigningKeyRecord>> listAllKeys() {
        return repository.findAll();
    }

    /**
     * Get a specific key by ID.
     */
    public Uni<SigningKeyRecord> getKey(String keyId) {
        return repository
                .findById(keyId)
                .map(opt -> opt.orElseThrow(() -> new KeyNotFoundException("Key not found: " + keyId)));
    }

    /**
     * Force deprecate a specific key.
     */
    public Uni<Void> forceDeprecate(String keyId) {
        LOG.warnv("Force deprecating key: {0}", keyId);
        return registry.deprecateKey(keyId);
    }

    /**
     * Force retire a specific key.
     *
     * <p>WARNING: This immediately invalidates all tokens signed with this key.
     */
    public Uni<Void> forceRetire(String keyId) {
        LOG.warnv("Force retiring key: {0} - tokens will be invalidated", keyId);
        return registry.retireKey(keyId);
    }

    /**
     * Exception thrown when a key is not found.
     */
    public static class KeyNotFoundException extends RuntimeException {
        public KeyNotFoundException(String message) {
            super(message);
        }
    }
}
