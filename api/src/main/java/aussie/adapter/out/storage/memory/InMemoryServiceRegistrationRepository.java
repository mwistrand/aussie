package aussie.adapter.out.storage.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
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
    private final AtomicLong generation = new AtomicLong();

    @Override
    public Uni<Void> save(ServiceRegistration registration) {
        return Uni.createFrom().item(() -> {
            storage.put(registration.serviceId(), registration);
            generation.incrementAndGet();
            return null;
        });
    }

    @Override
    public Uni<ConditionalWriteResult> createIfAbsent(ServiceRegistration registration) {
        return Uni.createFrom().item(() -> {
            final var existing = storage.putIfAbsent(registration.serviceId(), registration);
            if (existing == null) {
                generation.incrementAndGet();
                return ConditionalWriteResult.appliedResult();
            }
            return ConditionalWriteResult.rejected(existing.version());
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
                generation.incrementAndGet();
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
        return Uni.createFrom().item(() -> {
            final var removed = storage.remove(serviceId) != null;
            if (removed) {
                generation.incrementAndGet();
            }
            return removed;
        });
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
                generation.incrementAndGet();
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

    @Override
    public Uni<Long> currentGeneration() {
        return Uni.createFrom().item(generation::get);
    }
}
