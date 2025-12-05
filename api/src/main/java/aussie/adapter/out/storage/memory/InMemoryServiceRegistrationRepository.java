package aussie.adapter.out.storage.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import io.smallrye.mutiny.Uni;

import aussie.core.model.ServiceRegistration;
import aussie.core.port.out.ServiceRegistrationRepository;

/**
 * In-memory implementation of ServiceRegistrationRepository.
 *
 * <p>Data is NOT persisted across restarts. This implementation is suitable for:
 * <ul>
 *   <li>Development and testing</li>
 *   <li>Single-instance deployments where persistence is handled externally</li>
 *   <li>Fallback when no persistent storage provider is available</li>
 * </ul>
 */
public class InMemoryServiceRegistrationRepository implements ServiceRegistrationRepository {

    private final ConcurrentHashMap<String, ServiceRegistration> storage = new ConcurrentHashMap<>();

    @Override
    public Uni<Void> save(ServiceRegistration registration) {
        return Uni.createFrom().item(() -> {
            storage.put(registration.serviceId(), registration);
            return null;
        });
    }

    @Override
    public Uni<Optional<ServiceRegistration>> findById(String serviceId) {
        return Uni.createFrom().item(() -> Optional.ofNullable(storage.get(serviceId)));
    }

    @Override
    public Uni<Boolean> delete(String serviceId) {
        return Uni.createFrom().item(() -> storage.remove(serviceId) != null);
    }

    @Override
    public Uni<List<ServiceRegistration>> findAll() {
        return Uni.createFrom().item(() -> new ArrayList<>(storage.values()));
    }

    @Override
    public Uni<Boolean> exists(String serviceId) {
        return Uni.createFrom().item(() -> storage.containsKey(serviceId));
    }

    @Override
    public Uni<Long> count() {
        return Uni.createFrom().item(() -> (long) storage.size());
    }
}
