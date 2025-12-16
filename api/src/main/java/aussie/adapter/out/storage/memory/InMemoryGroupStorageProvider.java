package aussie.adapter.out.storage.memory;

import aussie.core.port.out.GroupRepository;
import aussie.spi.GroupStorageProvider;
import aussie.spi.StorageAdapterConfig;

/**
 * In-memory storage provider for groups.
 *
 * <p>Provides non-persistent storage suitable for development, testing,
 * and single-instance deployments where groups are managed externally.
 *
 * <p>Data is NOT persisted across application restarts.
 */
public class InMemoryGroupStorageProvider implements GroupStorageProvider {

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "In-memory group storage (non-persistent)";
    }

    @Override
    public int priority() {
        return 0; // Lowest priority, used as fallback
    }

    @Override
    public GroupRepository createRepository(StorageAdapterConfig config) {
        return new InMemoryGroupRepository();
    }
}
