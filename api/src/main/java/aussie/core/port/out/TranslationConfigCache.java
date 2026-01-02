package aussie.core.port.out;

import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.TranslationConfigVersion;

/**
 * Port interface for caching translation configurations.
 *
 * <p>Implementations provide distributed or local caching with TTL support.
 * The cache is used as an intermediate layer between the primary storage
 * (e.g., Cassandra) and the application.
 */
public interface TranslationConfigCache {

    /**
     * Get a cached config version by ID.
     *
     * @param id the version identifier
     * @return Uni with Optional containing the cached version if present
     */
    Uni<Optional<TranslationConfigVersion>> get(String id);

    /**
     * Get the cached active config version.
     *
     * @return Uni with Optional containing the active version if cached
     */
    Uni<Optional<TranslationConfigVersion>> getActive();

    /**
     * Cache a config version.
     *
     * @param version the version to cache
     * @return Uni completing when cached
     */
    Uni<Void> put(TranslationConfigVersion version);

    /**
     * Cache a config version as the active version.
     *
     * @param version the active version to cache
     * @return Uni completing when cached
     */
    Uni<Void> putActive(TranslationConfigVersion version);

    /**
     * Invalidate a cached version by ID.
     *
     * @param id the version identifier to invalidate
     * @return Uni completing when invalidated
     */
    Uni<Void> invalidate(String id);

    /**
     * Invalidate the active version cache.
     *
     * @return Uni completing when invalidated
     */
    Uni<Void> invalidateActive();

    /**
     * Invalidate all cached versions.
     *
     * @return Uni completing when all entries are invalidated
     */
    Uni<Void> invalidateAll();

    /**
     * Get the cached version list.
     *
     * @return Uni with Optional containing cached list if present
     */
    Uni<Optional<List<TranslationConfigVersion>>> getVersionList();

    /**
     * Cache the version list.
     *
     * @param versions the list to cache
     * @return Uni completing when cached
     */
    Uni<Void> putVersionList(List<TranslationConfigVersion> versions);

    /**
     * Invalidate the cached version list.
     *
     * @return Uni completing when invalidated
     */
    Uni<Void> invalidateVersionList();
}
