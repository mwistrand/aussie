package aussie.adapter.out.storage.memory;

import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.sampling.ServiceSamplingConfig;
import aussie.core.port.out.SamplingConfigRepository;

/**
 * In-memory implementation that always returns empty.
 *
 * <p>This implementation does not store any sampling configurations.
 * All lookups return empty, causing the resolver to use platform defaults.
 *
 * <p>For actual per-service sampling config storage, platform teams should
 * provide an implementation via the SamplingConfigProvider SPI that queries
 * their service registry or configuration store.
 */
public class InMemorySamplingConfigRepository implements SamplingConfigRepository {

    @Override
    public Uni<Optional<ServiceSamplingConfig>> findByServiceId(String serviceId) {
        return Uni.createFrom().item(Optional.empty());
    }
}
