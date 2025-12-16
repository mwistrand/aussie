package aussie.adapter.out.storage.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.Group;
import aussie.core.model.auth.GroupMapping;
import aussie.core.port.out.GroupRepository;

/**
 * In-memory implementation of GroupRepository.
 *
 * <p>Data is NOT persisted across restarts. This implementation is suitable for:
 * <ul>
 *   <li>Development and testing</li>
 *   <li>Single-instance deployments where persistence is handled externally</li>
 *   <li>Fallback when no persistent storage provider is available</li>
 * </ul>
 *
 * <p>For production use, configure a persistent storage provider via
 * aussie.auth.storage.provider=cassandra or implement a custom provider.
 *
 * <p>Thread-safety: Uses ConcurrentHashMap for safe concurrent access.
 */
public class InMemoryGroupRepository implements GroupRepository {

    private final ConcurrentHashMap<String, Group> storage = new ConcurrentHashMap<>();

    @Override
    public Uni<Void> save(Group group) {
        return Uni.createFrom().item(() -> {
            storage.put(group.id(), group);
            return null;
        });
    }

    @Override
    public Uni<Optional<Group>> findById(String groupId) {
        return Uni.createFrom().item(() -> Optional.ofNullable(storage.get(groupId)));
    }

    @Override
    public Uni<Boolean> delete(String groupId) {
        return Uni.createFrom().item(() -> storage.remove(groupId) != null);
    }

    @Override
    public Uni<List<Group>> findAll() {
        return Uni.createFrom().item(() -> new ArrayList<>(storage.values()));
    }

    @Override
    public Uni<Boolean> exists(String groupId) {
        return Uni.createFrom().item(() -> storage.containsKey(groupId));
    }

    @Override
    public Uni<GroupMapping> getGroupMapping() {
        return Uni.createFrom().item(() -> {
            final Map<String, Set<String>> mapping =
                    storage.values().stream().collect(Collectors.toMap(Group::id, Group::permissions));
            return new GroupMapping(mapping);
        });
    }
}
