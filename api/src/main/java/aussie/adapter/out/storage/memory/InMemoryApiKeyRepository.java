package aussie.adapter.out.storage.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.ApiKey;
import aussie.core.model.service.ConditionalWriteResult;
import aussie.core.port.out.ApiKeyRepository;

/**
 * In-memory implementation of ApiKeyRepository.
 *
 * <p>Data is NOT persisted across restarts. This implementation is suitable for:
 * <ul>
 *   <li>Development and testing</li>
 *   <li>Single-instance deployments where persistence is handled externally</li>
 *   <li>Fallback when no persistent storage provider is available</li>
 * </ul>
 *
 * <p>For production use, configure a persistent storage provider via
 * aussie.auth.storage.provider=cassandra or implement a custom provider.
 *
 * <p>This class is instantiated by {@link InMemoryAuthKeyStorageProvider}.
 *
 * <p>Thread-safety: Uses explicit synchronization to maintain consistency
 * between the two internal maps during write operations.
 */
public class InMemoryApiKeyRepository implements ApiKeyRepository {

    private final ConcurrentHashMap<String, ApiKey> storageById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ApiKey> storageByHash = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    @Override
    public Uni<Void> save(ApiKey apiKey) {
        return Uni.createFrom().item(() -> {
            synchronized (writeLock) {
                storageById.put(apiKey.id(), apiKey);
                storageByHash.put(apiKey.keyHash(), apiKey);
            }
            return null;
        });
    }

    @Override
    public Uni<ConditionalWriteResult> replaceIfVersion(ApiKey apiKey, long expectedVersion) {
        return Uni.createFrom().item(() -> {
            synchronized (writeLock) {
                final var current = storageById.get(apiKey.id());
                if (current == null) {
                    return ConditionalWriteResult.missing();
                }
                if (current.version() != expectedVersion) {
                    return ConditionalWriteResult.rejected(current.version());
                }
                storageById.put(apiKey.id(), apiKey);
                storageByHash.put(apiKey.keyHash(), apiKey);
                return ConditionalWriteResult.appliedResult();
            }
        });
    }

    @Override
    public Uni<Optional<ApiKey>> findById(String keyId) {
        return Uni.createFrom().item(() -> Optional.ofNullable(storageById.get(keyId)));
    }

    @Override
    public Uni<Optional<ApiKey>> findByHash(String keyHash) {
        return Uni.createFrom().item(() -> Optional.ofNullable(storageByHash.get(keyHash)));
    }

    @Override
    public Uni<Boolean> delete(String keyId) {
        return Uni.createFrom().item(() -> {
            synchronized (writeLock) {
                var apiKey = storageById.remove(keyId);
                if (apiKey != null) {
                    storageByHash.remove(apiKey.keyHash());
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public Uni<ConditionalWriteResult> deleteIfVersion(String keyId, long expectedVersion) {
        return Uni.createFrom().item(() -> {
            synchronized (writeLock) {
                final var current = storageById.get(keyId);
                if (current == null) {
                    return ConditionalWriteResult.missing();
                }
                if (current.version() != expectedVersion) {
                    return ConditionalWriteResult.rejected(current.version());
                }
                storageById.remove(keyId);
                storageByHash.remove(current.keyHash());
                return ConditionalWriteResult.appliedResult();
            }
        });
    }

    @Override
    public Uni<List<ApiKey>> findAll() {
        return Uni.createFrom().item(() -> new ArrayList<>(storageById.values()));
    }

    @Override
    public Uni<Boolean> exists(String keyId) {
        return Uni.createFrom().item(() -> storageById.containsKey(keyId));
    }
}
