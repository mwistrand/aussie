package aussie.adapter.out.telemetry;

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

import aussie.core.port.out.SamplingConfigRepository;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.spi.SamplingConfigProvider;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;

/**
 * Discovers and loads sampling config providers via ServiceLoader.
 *
 * <p>Provider selection:
 * <ol>
 *   <li>If aussie.telemetry.sampling.storage.provider is set, use that provider</li>
 *   <li>Otherwise, select the highest priority available provider</li>
 * </ol>
 *
 * <p>The default in-memory provider (priority 0) always returns empty configs,
 * meaning platform defaults are used for all services.
 */
@ApplicationScoped
public class SamplingConfigProviderLoader {

    private static final Logger LOG = Logger.getLogger(SamplingConfigProviderLoader.class);

    private final Optional<String> configuredProvider;
    private final StorageAdapterConfig config;

    private SamplingConfigProvider provider;

    @Inject
    public SamplingConfigProviderLoader(
            @ConfigProperty(name = "aussie.telemetry.sampling.storage.provider") Optional<String> configuredProvider,
            StorageAdapterConfig config) {
        this.configuredProvider = configuredProvider;
        this.config = config;
    }

    /**
     * Produces the SamplingConfigRepository bean.
     *
     * @return repository for sampling config lookups
     */
    @Produces
    @ApplicationScoped
    public SamplingConfigRepository repository() {
        final var selectedProvider = getProvider();
        LOG.infof(
                "Creating sampling config repository from provider: %s (%s)",
                selectedProvider.name(), selectedProvider.description());
        return selectedProvider.createRepository(config);
    }

    /**
     * Produces health indicators for sampling config storage.
     *
     * @return list of health indicators (may be empty)
     */
    @Produces
    @ApplicationScoped
    public List<StorageHealthIndicator> samplingHealthIndicators() {
        final List<StorageHealthIndicator> indicators = new ArrayList<>();
        getProvider().createHealthIndicator(config).ifPresent(indicators::add);
        return indicators;
    }

    private synchronized SamplingConfigProvider getProvider() {
        if (provider != null) {
            return provider;
        }

        final List<SamplingConfigProvider> providers = new ArrayList<>();
        ServiceLoader.load(SamplingConfigProvider.class).forEach(providers::add);

        if (providers.isEmpty()) {
            throw new StorageProviderException(
                    "No sampling config providers found. Ensure a provider JAR is on the classpath.");
        }

        LOG.infof(
                "Found %d sampling config provider(s): %s",
                providers.size(),
                providers.stream().map(SamplingConfigProvider::name).toList());

        provider = selectProvider(providers, configuredProvider.orElse(null));
        return provider;
    }

    private SamplingConfigProvider selectProvider(List<SamplingConfigProvider> providers, String configured) {
        // Explicit configuration takes precedence
        if (configured != null && !configured.isBlank()) {
            return providers.stream()
                    .filter(p -> p.name().equals(configured))
                    .findFirst()
                    .orElseThrow(() -> new StorageProviderException(
                            "Configured sampling config provider not found: " + configured + ". Available: "
                                    + providers.stream()
                                            .map(SamplingConfigProvider::name)
                                            .toList()));
        }

        // Otherwise, select by priority from available providers
        return providers.stream()
                .filter(SamplingConfigProvider::isAvailable)
                .max(Comparator.comparingInt(SamplingConfigProvider::priority))
                .orElseThrow(() -> new StorageProviderException("No available sampling config providers"));
    }
}
