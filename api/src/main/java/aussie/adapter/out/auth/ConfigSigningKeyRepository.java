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

    @Inject
    public ConfigSigningKeyRepository(RouteAuthConfig routeAuthConfig) {
        this.routeAuthConfig = routeAuthConfig;

        // Load initial key from configuration if available
        loadConfiguredKey();
    }

    @Override
    public boolean isDurable() {
        return false;
    }

    private void loadConfiguredKey() {
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
    public Uni<Boolean> storePendingIfAbsent(SigningKeyRecord key) {
        return Uni.createFrom().item(() -> {
            synchronized (keys) {
                if (key.status() != KeyStatus.PENDING
                        || keys.containsKey(key.keyId())
                        || keys.values().stream().anyMatch(existing -> existing.status() == KeyStatus.PENDING)) {
                    return false;
                }
                keys.put(key.keyId(), key);
                return true;
            }
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
                .filter(key -> key.status() != KeyStatus.RETIRED)
                .sorted((left, right) -> left.keyId().compareTo(right.keyId()))
                .toList());
    }

    @Override
    public Uni<List<SigningKeyRecord>> findByStatus(KeyStatus status) {
        return Uni.createFrom().item(() -> keys.values().stream()
                .filter(key -> key.status() == status)
                .toList());
    }

    @Override
    public Uni<Boolean> activate(String keyId, Optional<String> expectedActiveKeyId, Instant transitionTime) {
        return Uni.createFrom().item(() -> {
            synchronized (keys) {
                final var pending = keys.get(keyId);
                if (pending == null || pending.status() != KeyStatus.PENDING) {
                    return false;
                }

                final var activeKeys = keys.values().stream()
                        .filter(key -> key.status() == KeyStatus.ACTIVE)
                        .toList();
                if (activeKeys.size() > 1) {
                    return false;
                }
                final var active = activeKeys.stream().findFirst();
                if (!active.map(SigningKeyRecord::keyId).equals(expectedActiveKeyId)) {
                    return false;
                }

                active.ifPresent(key -> keys.put(key.keyId(), key.deprecate(transitionTime)));
                keys.put(keyId, pending.activate(transitionTime));
                return true;
            }
        });
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
