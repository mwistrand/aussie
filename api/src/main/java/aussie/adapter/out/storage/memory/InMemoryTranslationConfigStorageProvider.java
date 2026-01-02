package aussie.adapter.out.storage.memory;

import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.common.StorageHealth;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.core.port.out.TranslationConfigRepository;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.TranslationConfigStorageProvider;

/**
 * Default in-memory storage provider for translation configurations.
 *
 * <p>Data is NOT persisted across restarts. This provider exists as a
 * fallback for development/testing or single-instance deployments.
 */
public class InMemoryTranslationConfigStorageProvider implements TranslationConfigStorageProvider {

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "In-memory translation config storage (non-persistent)";
    }

    @Override
    public int priority() {
        return 0; // Lowest priority - only used if nothing else available
    }

    @Override
    public TranslationConfigRepository createRepository(StorageAdapterConfig config) {
        return new InMemoryTranslationConfigRepository();
    }

    @Override
    public Optional<StorageHealthIndicator> createHealthIndicator(StorageAdapterConfig config) {
        return Optional.of(() -> Uni.createFrom().item(StorageHealth.healthy("memory-translation-config", 0)));
    }
}
