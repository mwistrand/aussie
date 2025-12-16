package aussie.core.service.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.model.auth.Group;
import aussie.core.model.auth.GroupMapping;
import aussie.core.port.in.GroupManagement;
import aussie.core.port.out.GroupRepository;

/**
 * Service for managing RBAC groups.
 *
 * <p>Handles group CRUD operations and maintains a cached mapping of groups
 * to permissions for efficient token validation.
 *
 * <p>The cache is invalidated on any write operation (create, update, delete).
 */
@ApplicationScoped
public class GroupService implements GroupManagement {

    private static final Logger LOG = Logger.getLogger(GroupService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final GroupRepository repository;

    // Simple in-memory cache for group mapping
    private volatile GroupMapping cachedMapping;
    private volatile Instant cacheExpiry = Instant.MIN;
    private final Object cacheLock = new Object();

    @Inject
    public GroupService(GroupRepository repository) {
        this.repository = repository;
    }

    @Override
    public Uni<Group> create(String id, String displayName, String description, Set<String> permissions) {
        if (id == null || id.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Group ID cannot be null or blank"));
        }

        return repository.exists(id).flatMap(exists -> {
            if (exists) {
                return Uni.createFrom()
                        .failure(new IllegalArgumentException("Group with ID '" + id + "' already exists"));
            }

            final var group = Group.builder(id)
                    .displayName(displayName)
                    .description(description)
                    .permissions(permissions != null ? permissions : Set.of())
                    .build();

            return repository.save(group).invoke(this::invalidateCache).replaceWith(group);
        });
    }

    @Override
    public Uni<Optional<Group>> updatePermissions(String id, Set<String> permissions) {
        return repository.findById(id).flatMap(existing -> {
            if (existing.isEmpty()) {
                return Uni.createFrom().item(Optional.<Group>empty());
            }

            final var updated = existing.get().withPermissions(permissions);
            return repository.save(updated).invoke(this::invalidateCache).replaceWith(Optional.of(updated));
        });
    }

    @Override
    public Uni<Optional<Group>> update(String id, String displayName, String description, Set<String> permissions) {
        return repository.findById(id).flatMap(existing -> {
            if (existing.isEmpty()) {
                return Uni.createFrom().item(Optional.<Group>empty());
            }

            final var current = existing.get();
            final var updated = Group.builder(id)
                    .displayName(displayName != null ? displayName : current.displayName())
                    .description(description != null ? description : current.description())
                    .permissions(permissions != null ? permissions : current.permissions())
                    .createdAt(current.createdAt())
                    .updatedAt(Instant.now())
                    .build();

            return repository.save(updated).invoke(this::invalidateCache).replaceWith(Optional.of(updated));
        });
    }

    @Override
    public Uni<Optional<Group>> get(String id) {
        return repository.findById(id);
    }

    @Override
    public Uni<List<Group>> list() {
        return repository.findAll();
    }

    @Override
    public Uni<Boolean> delete(String id) {
        return repository.delete(id).invoke(deleted -> {
            if (deleted) {
                invalidateCache();
            }
        });
    }

    @Override
    public Uni<GroupMapping> getGroupMapping() {
        // Check cache first - must read volatile fields atomically
        final GroupMapping cached;
        final Instant expiry;
        synchronized (cacheLock) {
            cached = this.cachedMapping;
            expiry = this.cacheExpiry;
        }

        final var now = Instant.now();
        if (cached != null && now.isBefore(expiry)) {
            return Uni.createFrom().item(cached);
        }

        // Refresh cache
        return repository.getGroupMapping().invoke(mapping -> {
            synchronized (cacheLock) {
                this.cachedMapping = mapping;
                this.cacheExpiry = now.plus(CACHE_TTL);
            }
            LOG.debugf("Refreshed group mapping cache with %d groups", mapping.size());
        });
    }

    @Override
    public Uni<Set<String>> expandGroups(Set<String> groups) {
        if (groups == null || groups.isEmpty()) {
            return Uni.createFrom().item(Set.of());
        }

        return getGroupMapping().map(mapping -> mapping.expandGroups(groups));
    }

    /**
     * Invalidate the cached group mapping.
     *
     * <p>Called after any write operation to ensure the cache is refreshed.
     */
    private void invalidateCache() {
        synchronized (cacheLock) {
            this.cachedMapping = null;
            this.cacheExpiry = Instant.MIN;
        }
        LOG.debug("Invalidated group mapping cache");
    }
}
