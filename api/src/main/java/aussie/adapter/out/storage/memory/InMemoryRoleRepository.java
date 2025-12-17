package aussie.adapter.out.storage.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.Role;
import aussie.core.model.auth.RoleMapping;
import aussie.core.port.out.RoleRepository;

/**
 * In-memory implementation of RoleRepository.
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
public class InMemoryRoleRepository implements RoleRepository {

    private final ConcurrentHashMap<String, Role> storage = new ConcurrentHashMap<>();

    @Override
    public Uni<Void> save(Role role) {
        return Uni.createFrom().item(() -> {
            storage.put(role.id(), role);
            return null;
        });
    }

    @Override
    public Uni<Optional<Role>> findById(String roleId) {
        return Uni.createFrom().item(() -> Optional.ofNullable(storage.get(roleId)));
    }

    @Override
    public Uni<Boolean> delete(String roleId) {
        return Uni.createFrom().item(() -> storage.remove(roleId) != null);
    }

    @Override
    public Uni<List<Role>> findAll() {
        return Uni.createFrom().item(() -> new ArrayList<>(storage.values()));
    }

    @Override
    public Uni<Boolean> exists(String roleId) {
        return Uni.createFrom().item(() -> storage.containsKey(roleId));
    }

    @Override
    public Uni<RoleMapping> getRoleMapping() {
        return Uni.createFrom().item(() -> {
            final Map<String, Set<String>> mapping =
                    storage.values().stream().collect(Collectors.toMap(Role::id, Role::permissions));
            return new RoleMapping(mapping);
        });
    }
}
