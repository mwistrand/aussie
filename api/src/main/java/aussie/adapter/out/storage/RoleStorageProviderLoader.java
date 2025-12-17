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

import aussie.core.port.out.RoleRepository;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.spi.RoleStorageProvider;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;

/**
 * Discovers and loads role storage providers via ServiceLoader.
 *
 * <p>Provider selection:
 * <ol>
 *   <li>If aussie.auth.roles.storage.provider is set, use that provider</li>
 *   <li>Otherwise, select the highest priority available provider</li>
 * </ol>
 *
 * <p>Thread-safety: Uses synchronized methods for lazy provider initialization
 * to ensure thread-safe access from CDI producer methods.
 */
@ApplicationScoped
public class RoleStorageProviderLoader {

    private static final Logger LOG = Logger.getLogger(RoleStorageProviderLoader.class);

    private final Optional<String> configuredStorageProvider;
    private final StorageAdapterConfig config;

    private RoleStorageProvider storageProvider;

    @Inject
    public RoleStorageProviderLoader(
            @ConfigProperty(name = "aussie.auth.roles.storage.provider") Optional<String> configuredStorageProvider,
            StorageAdapterConfig config) {
        this.configuredStorageProvider = configuredStorageProvider;
        this.config = config;
    }

    @Produces
    @ApplicationScoped
    public RoleRepository roleRepository() {
        final var provider = getStorageProvider();
        LOG.infof("Creating role repository from provider: %s (%s)", provider.name(), provider.description());
        return provider.createRepository(config);
    }

    @Produces
    @ApplicationScoped
    public List<StorageHealthIndicator> roleHealthIndicators() {
        final List<StorageHealthIndicator> indicators = new ArrayList<>();
        getStorageProvider().createHealthIndicator(config).ifPresent(indicators::add);
        return indicators;
    }

    private synchronized RoleStorageProvider getStorageProvider() {
        if (storageProvider != null) {
            return storageProvider;
        }

        final List<RoleStorageProvider> providers = new ArrayList<>();
        ServiceLoader.load(RoleStorageProvider.class).forEach(providers::add);

        if (providers.isEmpty()) {
            throw new StorageProviderException(
                    "No role storage providers found. Ensure a provider JAR is on the classpath.");
        }

        LOG.infof(
                "Found %d role storage provider(s): %s",
                providers.size(),
                providers.stream().map(RoleStorageProvider::name).toList());

        storageProvider = selectProvider(providers, configuredStorageProvider.orElse(null));

        return storageProvider;
    }

    private RoleStorageProvider selectProvider(List<RoleStorageProvider> providers, String configured) {
        if (configured != null && !configured.isBlank()) {
            return providers.stream()
                    .filter(p -> p.name().equals(configured))
                    .findFirst()
                    .orElseThrow(() -> new StorageProviderException("Configured role storage provider not found: "
                            + configured + ". Available: "
                            + providers.stream().map(RoleStorageProvider::name).toList()));
        }

        return providers.stream()
                .filter(RoleStorageProvider::isAvailable)
                .max(Comparator.comparingInt(RoleStorageProvider::priority))
                .orElseThrow(() -> new StorageProviderException("No available role storage providers"));
    }
}
