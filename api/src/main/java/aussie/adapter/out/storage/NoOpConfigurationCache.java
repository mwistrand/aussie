package aussie.adapter.out.storage;

import java.time.Duration;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.ServiceRegistration;
import aussie.core.port.out.ConfigurationCache;

/**
 * No-operation cache implementation for when caching is disabled.
 *
 * <p>All operations are no-ops that complete immediately.
 * This allows the system to work without a real cache while
 * maintaining the same code path.
 */
public class NoOpConfigurationCache implements ConfigurationCache {

    public static final NoOpConfigurationCache INSTANCE = new NoOpConfigurationCache();

    private NoOpConfigurationCache() {}

    @Override
    public Uni<Optional<ServiceRegistration>> get(String serviceId) {
        return Uni.createFrom().item(Optional.empty());
    }

    @Override
    public Uni<Void> put(ServiceRegistration service) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> put(ServiceRegistration service, Duration ttl) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> invalidate(String serviceId) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> invalidateAll() {
        return Uni.createFrom().voidItem();
    }
}
