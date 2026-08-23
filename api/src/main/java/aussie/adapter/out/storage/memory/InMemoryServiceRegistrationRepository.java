package aussie.adapter.out.storage.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import io.smallrye.mutiny.Uni;

import aussie.core.model.service.ConditionalWriteResult;
import aussie.core.model.service.ServiceRegistration;
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
    public Uni<ConditionalWriteResult> createIfAbsent(ServiceRegistration registration) {
        return Uni.createFrom().item(() -> {
            final var existing = storage.putIfAbsent(registration.serviceId(), registration);
            return existing == null
                    ? ConditionalWriteResult.appliedResult()
                    : ConditionalWriteResult.rejected(existing.version());
        });
    }

    @Override
    public Uni<ConditionalWriteResult> replaceIfVersion(ServiceRegistration registration, long expectedVersion) {
        return Uni.createFrom().item(() -> {
            final var result = new AtomicReference<ConditionalWriteResult>();
            storage.compute(registration.serviceId(), (ignored, existing) -> {
                if (existing == null) {
                    result.set(ConditionalWriteResult.missing());
                    return null;
                }
                if (existing.version() != expectedVersion) {
                    result.set(ConditionalWriteResult.rejected(existing.version()));
                    return existing;
                }
                result.set(ConditionalWriteResult.appliedResult());
                return registration;
            });
            return result.get();
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
    public Uni<ConditionalWriteResult> deleteIfVersion(String serviceId, long expectedVersion) {
        return Uni.createFrom().item(() -> {
            final var result = new AtomicReference<ConditionalWriteResult>();
            storage.compute(serviceId, (ignored, existing) -> {
                if (existing == null) {
                    result.set(ConditionalWriteResult.missing());
                    return null;
                }
                if (existing.version() != expectedVersion) {
                    result.set(ConditionalWriteResult.rejected(existing.version()));
                    return existing;
                }
                result.set(ConditionalWriteResult.appliedResult());
                return null;
            });
            return result.get();
        });
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
