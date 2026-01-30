package aussie.adapter.out.storage.memory;

import aussie.core.port.out.SamplingConfigRepository;
import aussie.spi.SamplingConfigProvider;
import aussie.spi.StorageAdapterConfig;

/**
 * Default in-memory sampling config provider.
 *
 * <p>This provider returns an empty sampling config for all services,
 * meaning the platform default sampling rate will always be used.
 *
 * <p>This is the fallback provider when no external storage is configured.
 * It is suitable for:
 * <ul>
 *   <li>Development environments</li>
 *   <li>Deployments where sampling rates are managed via platform defaults</li>
 *   <li>Single-instance deployments without service-level overrides</li>
 * </ul>
 *
 * <p>For production deployments requiring per-service sampling configuration,
 * platform teams should implement a custom provider that integrates with their
 * service registry or configuration management system.
 */
public class InMemorySamplingConfigProvider implements SamplingConfigProvider {

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public String description() {
        return "In-memory sampling config (uses platform defaults)";
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public SamplingConfigRepository createRepository(StorageAdapterConfig config) {
        return new InMemorySamplingConfigRepository();
    }
}
