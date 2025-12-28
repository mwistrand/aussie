package aussie.adapter.out.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.config.KeyRotationConfig;
import aussie.core.config.RouteAuthConfig;
import aussie.core.model.auth.KeyStatus;
import aussie.core.model.auth.SigningKeyRecord;
import aussie.spi.SigningKeyRepository;

/**
 * Configuration-based signing key repository.
 *
 * <p>
 * This is the default implementation that:
 * <ul>
 * <li>Loads initial key from configuration (for backward compatibility)</li>
 * <li>Stores additional keys in memory (not persisted)</li>
 * </ul>
 *
 * <p>
 * <strong>Warning:</strong> Keys stored via {@link #store(SigningKeyRecord)}
 * are
 * lost on restart and not shared across instances. For production use with key
 * rotation, implement a persistent {@link SigningKeyRepository} (e.g., Vault,
 * database).
 *
 * <p>
 * When key rotation is disabled, this repository provides the static key
 * from {@code aussie.auth.route-auth.jws.signing-key} configuration.
 */
@ApplicationScoped
public class ConfigSigningKeyRepository implements SigningKeyRepository {

    private static final Logger LOG = Logger.getLogger(ConfigSigningKeyRepository.class);

    private final ConcurrentMap<String, SigningKeyRecord> keys = new ConcurrentHashMap<>();
    private final RouteAuthConfig routeAuthConfig;
    private final KeyRotationConfig keyRotationConfig;

    @Inject
    public ConfigSigningKeyRepository(RouteAuthConfig routeAuthConfig, KeyRotationConfig keyRotationConfig) {
        this.routeAuthConfig = routeAuthConfig;
        this.keyRotationConfig = keyRotationConfig;

        // Load initial key from configuration if available
        loadConfiguredKey();
    }

    private void loadConfiguredKey() {
        if (!routeAuthConfig.enabled()) {
            LOG.debug("Route auth disabled, no initial signing key loaded");
            return;
        }

        final var signingKeyOpt = routeAuthConfig.jws().signingKey();
        if (signingKeyOpt.isEmpty()) {
            LOG.debug("No signing key configured");
            return;
        }

        try {
            final var privateKey = SigningKeyRecord.parsePrivateKey(signingKeyOpt.get());
            final var publicKey = SigningKeyRecord.derivePublicKey(privateKey);
            final var keyId = routeAuthConfig.jws().keyId();

            final var keyRecord = SigningKeyRecord.active(keyId, privateKey, publicKey);
            keys.put(keyId, keyRecord);

            LOG.infov("Loaded configured signing key: {0}", keyId);
        } catch (IllegalArgumentException e) {
            LOG.errorv(e, "Failed to load configured signing key");
        }
    }

    @Override
    public Uni<Void> store(SigningKeyRecord key) {
        return Uni.createFrom().item(() -> {
            keys.put(key.keyId(), key);
            LOG.debugv("Stored signing key: {0} (status: {1})", key.keyId(), key.status());
            return null;
        });
    }

    @Override
    public Uni<Optional<SigningKeyRecord>> findById(String keyId) {
        return Uni.createFrom().item(() -> Optional.ofNullable(keys.get(keyId)));
    }

    @Override
    public Uni<Optional<SigningKeyRecord>> findActive() {
        return Uni.createFrom().item(() -> keys.values().stream()
                .filter(key -> key.status() == KeyStatus.ACTIVE)
                .max((a, b) -> {
                    // If multiple ACTIVE keys (shouldn't happen), prefer most recently activated
                    final var aTime = a.activatedAt() != null ? a.activatedAt() : a.createdAt();
                    final var bTime = b.activatedAt() != null ? b.activatedAt() : b.createdAt();
                    return aTime.compareTo(bTime);
                }));
    }

    @Override
    public Uni<List<SigningKeyRecord>> findAllForVerification() {
        return Uni.createFrom().item(() -> keys.values().stream()
                .filter(key -> key.status() == KeyStatus.ACTIVE || key.status() == KeyStatus.DEPRECATED)
                .toList());
    }

    @Override
    public Uni<List<SigningKeyRecord>> findByStatus(KeyStatus status) {
        return Uni.createFrom().item(() -> keys.values().stream()
                .filter(key -> key.status() == status)
                .toList());
    }

    @Override
    public Uni<Void> updateStatus(String keyId, KeyStatus newStatus, Instant transitionTime) {
        return Uni.createFrom().item(() -> {
            final var existing = keys.get(keyId);
            if (existing == null) {
                throw new IllegalArgumentException("Key not found: " + keyId);
            }

            final var updated =
                    switch (newStatus) {
                        case ACTIVE -> existing.activate(transitionTime);
                        case DEPRECATED -> existing.deprecate(transitionTime);
                        case RETIRED -> existing.retire(transitionTime);
                        case PENDING -> existing; // No change for PENDING -> PENDING
                    };

            keys.put(keyId, updated);
            LOG.debugv("Updated key {0} status to {1}", keyId, newStatus);
            return null;
        });
    }

    @Override
    public Uni<Void> delete(String keyId) {
        return Uni.createFrom().item(() -> {
            final var removed = keys.remove(keyId);
            if (removed != null) {
                LOG.debugv("Deleted key: {0}", keyId);
            }
            return null;
        });
    }

    @Override
    public Uni<List<SigningKeyRecord>> findAll() {
        return Uni.createFrom().item(() -> List.copyOf(keys.values()));
    }

    /**
     * Returns the number of keys in the repository.
     *
     * @return count of stored keys
     */
    public int getKeyCount() {
        return keys.size();
    }

    /**
     * Removes all keys from the repository.
     */
    public void clear() {
        keys.clear();
    }
}
