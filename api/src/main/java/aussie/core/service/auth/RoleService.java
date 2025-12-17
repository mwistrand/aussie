package aussie.core.service.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.model.auth.Role;
import aussie.core.model.auth.RoleMapping;
import aussie.core.port.in.RoleManagement;
import aussie.core.port.out.RoleRepository;

/**
 * Service for managing RBAC roles.
 *
 * <p>Handles role CRUD operations and maintains a cached mapping of roles
 * to permissions for efficient token validation.
 *
 * <p>The cache is invalidated on any write operation (create, update, delete).
 */
@ApplicationScoped
public class RoleService implements RoleManagement {

    private static final Logger LOG = Logger.getLogger(RoleService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final RoleRepository repository;

    // Simple in-memory cache for role mapping
    private volatile RoleMapping cachedMapping;
    private volatile Instant cacheExpiry = Instant.MIN;
    private final Object cacheLock = new Object();

    @Inject
    public RoleService(RoleRepository repository) {
        this.repository = repository;
    }

    @Override
    public Uni<Role> create(String id, String displayName, String description, Set<String> permissions) {
        if (id == null || id.isBlank()) {
            return Uni.createFrom().failure(new IllegalArgumentException("Role ID cannot be null or blank"));
        }

        return repository.exists(id).flatMap(exists -> {
            if (exists) {
                return Uni.createFrom()
                        .failure(new IllegalArgumentException("Role with ID '" + id + "' already exists"));
            }

            final var role = Role.builder(id)
                    .displayName(displayName)
                    .description(description)
                    .permissions(permissions != null ? permissions : Set.of())
                    .build();

            return repository.save(role).invoke(this::invalidateCache).replaceWith(role);
        });
    }

    @Override
    public Uni<Optional<Role>> updatePermissions(String id, Set<String> permissions) {
        return repository.findById(id).flatMap(existing -> {
            if (existing.isEmpty()) {
                return Uni.createFrom().item(Optional.<Role>empty());
            }

            final var updated = existing.get().withPermissions(permissions);
            return repository.save(updated).invoke(this::invalidateCache).replaceWith(Optional.of(updated));
        });
    }

    @Override
    public Uni<Optional<Role>> update(String id, String displayName, String description, Set<String> permissions) {
        return update(id, displayName, description, permissions, null, null);
    }

    /**
     * Update an existing role with support for incremental permission changes.
     *
     * @param id                the role ID to update
     * @param displayName       new display name (null to keep current)
     * @param description       new description (null to keep current)
     * @param permissions       new permissions (null to keep current, mutually exclusive with add/remove)
     * @param addPermissions    permissions to add (null to skip)
     * @param removePermissions permissions to remove (null to skip)
     * @return Uni with the updated role, or empty if not found
     */
    public Uni<Optional<Role>> update(
            String id,
            String displayName,
            String description,
            Set<String> permissions,
            Set<String> addPermissions,
            Set<String> removePermissions) {

        return repository.findById(id).flatMap(existing -> {
            if (existing.isEmpty()) {
                return Uni.createFrom().item(Optional.<Role>empty());
            }

            final var current = existing.get();

            // Determine final permissions
            Set<String> finalPermissions;
            if (permissions != null) {
                // Full replacement
                finalPermissions = permissions;
            } else {
                // Incremental update
                finalPermissions = new HashSet<>(current.permissions());
                if (addPermissions != null) {
                    finalPermissions.addAll(addPermissions);
                }
                if (removePermissions != null) {
                    finalPermissions.removeAll(removePermissions);
                }
            }

            final var updated = Role.builder(id)
                    .displayName(displayName != null ? displayName : current.displayName())
                    .description(description != null ? description : current.description())
                    .permissions(finalPermissions)
                    .createdAt(current.createdAt())
                    .updatedAt(Instant.now())
                    .build();

            return repository.save(updated).invoke(this::invalidateCache).replaceWith(Optional.of(updated));
        });
    }

    @Override
    public Uni<Optional<Role>> get(String id) {
        return repository.findById(id);
    }

    @Override
    public Uni<List<Role>> list() {
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
    public Uni<RoleMapping> getRoleMapping() {
        // Check cache first - must read volatile fields atomically
        final RoleMapping cached;
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
        return repository.getRoleMapping().invoke(mapping -> {
            synchronized (cacheLock) {
                this.cachedMapping = mapping;
                this.cacheExpiry = now.plus(CACHE_TTL);
            }
            LOG.debugf("Refreshed role mapping cache with %d roles", mapping.size());
        });
    }

    @Override
    public Uni<Set<String>> expandRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Uni.createFrom().item(Set.of());
        }

        return getRoleMapping().map(mapping -> mapping.expandRoles(roles));
    }

    /**
     * Invalidate the cached role mapping.
     *
     * <p>Called after any write operation to ensure the cache is refreshed.
     */
    private void invalidateCache() {
        synchronized (cacheLock) {
            this.cachedMapping = null;
            this.cacheExpiry = Instant.MIN;
        }
        LOG.debug("Invalidated role mapping cache");
    }
}
