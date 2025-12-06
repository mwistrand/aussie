package aussie.adapter.out.storage.memory;

import aussie.core.port.out.ApiKeyRepository;
import aussie.spi.AuthKeyStorageProvider;
import aussie.spi.StorageAdapterConfig;

/**
 * In-memory storage provider for API keys.
 *
 * <p>Provides non-persistent storage suitable for development, testing,
 * and single-instance deployments where API keys are managed externally.
 *
 * <p>Data is NOT persisted across application restarts.
 */
public class InMemoryAuthKeyStorageProvider implements AuthKeyStorageProvider {

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "In-memory API key storage (non-persistent)";
    }

    @Override
    public int priority() {
        return 0; // Lowest priority, used as fallback
    }

    @Override
    public ApiKeyRepository createRepository(StorageAdapterConfig config) {
        return new InMemoryApiKeyRepository();
    }
}
