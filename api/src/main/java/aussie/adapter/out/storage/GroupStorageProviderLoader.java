package aussie.adapter.out.storage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import aussie.core.port.out.GroupRepository;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.spi.GroupStorageProvider;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;

/**
 * Discovers and loads group storage providers via ServiceLoader.
 *
 * <p>Provider selection:
 * <ol>
 *   <li>If aussie.auth.groups.storage.provider is set, use that provider</li>
 *   <li>Otherwise, select the highest priority available provider</li>
 * </ol>
 *
 * <p>Thread-safety: Uses synchronized methods for lazy provider initialization
 * to ensure thread-safe access from CDI producer methods.
 */
@ApplicationScoped
public class GroupStorageProviderLoader {

    private static final Logger LOG = Logger.getLogger(GroupStorageProviderLoader.class);

    private final Optional<String> configuredStorageProvider;
    private final StorageAdapterConfig config;

    private GroupStorageProvider storageProvider;

    @Inject
    public GroupStorageProviderLoader(
            @ConfigProperty(name = "aussie.auth.groups.storage.provider") Optional<String> configuredStorageProvider,
            StorageAdapterConfig config) {
        this.configuredStorageProvider = configuredStorageProvider;
        this.config = config;
    }

    @Produces
    @ApplicationScoped
    public GroupRepository groupRepository() {
        final var provider = getStorageProvider();
        LOG.infof("Creating group repository from provider: %s (%s)", provider.name(), provider.description());
        return provider.createRepository(config);
    }

    @Produces
    @ApplicationScoped
    public List<StorageHealthIndicator> groupHealthIndicators() {
        final List<StorageHealthIndicator> indicators = new ArrayList<>();
        getStorageProvider().createHealthIndicator(config).ifPresent(indicators::add);
        return indicators;
    }

    private synchronized GroupStorageProvider getStorageProvider() {
        if (storageProvider != null) {
            return storageProvider;
        }

        final List<GroupStorageProvider> providers = new ArrayList<>();
        ServiceLoader.load(GroupStorageProvider.class).forEach(providers::add);

        if (providers.isEmpty()) {
            throw new StorageProviderException(
                    "No group storage providers found. Ensure a provider JAR is on the classpath.");
        }

        LOG.infof(
                "Found %d group storage provider(s): %s",
                providers.size(),
                providers.stream().map(GroupStorageProvider::name).toList());

        storageProvider = selectProvider(providers, configuredStorageProvider.orElse(null));

        return storageProvider;
    }

    private GroupStorageProvider selectProvider(List<GroupStorageProvider> providers, String configured) {
        if (configured != null && !configured.isBlank()) {
            return providers.stream()
                    .filter(p -> p.name().equals(configured))
                    .findFirst()
                    .orElseThrow(() -> new StorageProviderException("Configured group storage provider not found: "
                            + configured + ". Available: "
                            + providers.stream().map(GroupStorageProvider::name).toList()));
        }

        return providers.stream()
                .filter(GroupStorageProvider::isAvailable)
                .max(Comparator.comparingInt(GroupStorageProvider::priority))
                .orElseThrow(() -> new StorageProviderException("No available group storage providers"));
    }
}
