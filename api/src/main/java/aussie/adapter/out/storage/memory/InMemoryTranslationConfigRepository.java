package aussie.adapter.out.storage.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.TranslationConfigVersion;
import aussie.core.port.out.TranslationConfigRepository;

/**
 * In-memory implementation of TranslationConfigRepository.
 *
 * <p>Data is NOT persisted across restarts. This implementation is suitable for:
 * <ul>
 *   <li>Development and testing</li>
 *   <li>Single-instance deployments</li>
 * </ul>
 *
 * <p>Thread-safety: Uses ConcurrentHashMap and AtomicInteger for safe concurrent access.
 */
public class InMemoryTranslationConfigRepository implements TranslationConfigRepository {

    private final ConcurrentHashMap<String, TranslationConfigVersion> storageById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> versionToId = new ConcurrentHashMap<>();
    private final AtomicInteger versionCounter = new AtomicInteger(0);
    private final AtomicReference<String> activeVersionId = new AtomicReference<>();

    @Override
    public Uni<Void> save(TranslationConfigVersion version) {
        return Uni.createFrom().item(() -> {
            storageById.put(version.id(), version);
            versionToId.put(version.version(), version.id());
            return null;
        });
    }

    @Override
    public Uni<Optional<TranslationConfigVersion>> getActive() {
        return Uni.createFrom().item(() -> {
            final var activeId = activeVersionId.get();
            if (activeId == null) {
                return Optional.empty();
            }
            final var version = storageById.get(activeId);
            return Optional.ofNullable(version).map(TranslationConfigVersion::activate);
        });
    }

    @Override
    public Uni<Optional<TranslationConfigVersion>> findById(String id) {
        return Uni.createFrom().item(() -> {
            final var version = storageById.get(id);
            if (version == null) {
                return Optional.empty();
            }
            return Optional.of(id.equals(activeVersionId.get()) ? version.activate() : version.deactivate());
        });
    }

    @Override
    public Uni<Optional<TranslationConfigVersion>> findByVersion(int versionNumber) {
        return Uni.createFrom().item(() -> {
            final var id = versionToId.get(versionNumber);
            if (id == null) {
                return Optional.empty();
            }
            final var version = storageById.get(id);
            if (version == null) {
                return Optional.empty();
            }
            return Optional.of(id.equals(activeVersionId.get()) ? version.activate() : version.deactivate());
        });
    }

    @Override
    public Uni<List<TranslationConfigVersion>> listVersions() {
        return Uni.createFrom().item(() -> {
            final var activeId = activeVersionId.get();
            return storageById.values().stream()
                    .map(v -> v.id().equals(activeId) ? v.activate() : v.deactivate())
                    .sorted(Comparator.comparing(TranslationConfigVersion::version)
                            .reversed())
                    .toList();
        });
    }

    @Override
    public Uni<List<TranslationConfigVersion>> listVersions(int limit, int offset) {
        return Uni.createFrom().item(() -> {
            final var all = new ArrayList<>(storageById.values());
            all.sort(Comparator.comparing(TranslationConfigVersion::version).reversed());
            final var activeId = activeVersionId.get();

            return all.stream()
                    .skip(offset)
                    .limit(limit)
                    .map(v -> v.id().equals(activeId) ? v.activate() : v.deactivate())
                    .toList();
        });
    }

    @Override
    public Uni<Integer> getNextVersionNumber() {
        return Uni.createFrom().item(versionCounter::incrementAndGet);
    }

    @Override
    public Uni<Boolean> setActive(String versionId) {
        return Uni.createFrom().item(() -> {
            if (!storageById.containsKey(versionId)) {
                return false;
            }
            activeVersionId.set(versionId);
            return true;
        });
    }

    @Override
    public Uni<Boolean> delete(String versionId) {
        return Uni.createFrom().item(() -> {
            if (versionId.equals(activeVersionId.get())) {
                return false;
            }
            final var removed = storageById.remove(versionId);
            if (removed != null) {
                versionToId.remove(removed.version());
                return true;
            }
            return false;
        });
    }

    /**
     * Clears all stored data. Intended for testing purposes.
     */
    public void clear() {
        storageById.clear();
        versionToId.clear();
        versionCounter.set(0);
        activeVersionId.set(null);
    }
}
