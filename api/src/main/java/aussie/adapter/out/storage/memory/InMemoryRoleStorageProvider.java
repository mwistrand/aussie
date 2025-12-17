package aussie.adapter.out.storage.memory;

import aussie.core.port.out.RoleRepository;
import aussie.spi.RoleStorageProvider;
import aussie.spi.StorageAdapterConfig;

/**
 * In-memory storage provider for roles.
 *
 * <p>Provides non-persistent storage suitable for development, testing,
 * and single-instance deployments where roles are managed externally.
 *
 * <p>Data is NOT persisted across application restarts.
 */
public class InMemoryRoleStorageProvider implements RoleStorageProvider {

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "In-memory role storage (non-persistent)";
    }

    @Override
    public int priority() {
        return 0; // Lowest priority, used as fallback
    }

    @Override
    public RoleRepository createRepository(StorageAdapterConfig config) {
        return new InMemoryRoleRepository();
    }
}
