package aussie.adapter.out.storage.memory;

import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.StorageHealth;
import aussie.core.port.out.ServiceRegistrationRepository;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageRepositoryProvider;

/**
 * Default in-memory storage provider.
 *
 * <p>Data is NOT persisted across restarts. This provider exists as a
 * fallback for development/testing or single-instance deployments
 * where persistence is handled externally.
 */
public class InMemoryStorageProvider implements StorageRepositoryProvider {

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "In-memory storage (non-persistent)";
    }

    @Override
    public int priority() {
        return 0; // Lowest priority - only used if nothing else available
    }

    @Override
    public ServiceRegistrationRepository createRepository(StorageAdapterConfig config) {
        return new InMemoryServiceRegistrationRepository();
    }

    @Override
    public Optional<StorageHealthIndicator> createHealthIndicator(StorageAdapterConfig config) {
        return Optional.of(() -> Uni.createFrom().item(StorageHealth.healthy("memory", 0)));
    }
}
