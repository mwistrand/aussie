package aussie.core.port.out;

import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.auth.TranslationConfigVersion;

/**
 * Port interface for persistent storage of translation configurations.
 *
 * <p>Configs are stored with version history for auditing and rollback.
 * Only one config version is active at a time.
 */
public interface TranslationConfigRepository {

    /**
     * Save a new config version.
     *
     * @param version the config version to persist
     * @return Uni completing when save is durable
     */
    Uni<Void> save(TranslationConfigVersion version);

    /**
     * Get the currently active config version.
     *
     * @return Uni with Optional containing the active version if one exists
     */
    Uni<Optional<TranslationConfigVersion>> getActive();

    /**
     * Find a config version by its ID.
     *
     * @param id the version identifier
     * @return Uni with Optional containing the version if found
     */
    Uni<Optional<TranslationConfigVersion>> findById(String id);

    /**
     * Find a config version by its sequential version number.
     *
     * @param versionNumber the sequential version number
     * @return Uni with Optional containing the version if found
     */
    Uni<Optional<TranslationConfigVersion>> findByVersion(int versionNumber);

    /**
     * List all config versions, ordered by version number descending.
     *
     * @return Uni with list of all versions
     */
    Uni<List<TranslationConfigVersion>> listVersions();

    /**
     * List config versions with pagination.
     *
     * @param limit maximum number of versions to return
     * @param offset number of versions to skip
     * @return Uni with list of versions
     */
    Uni<List<TranslationConfigVersion>> listVersions(int limit, int offset);

    /**
     * Get the next sequential version number.
     *
     * @return Uni with the next version number
     */
    Uni<Integer> getNextVersionNumber();

    /**
     * Set a specific version as active, deactivating any previous active version.
     *
     * @param versionId the version ID to activate
     * @return Uni with true if version was found and activated
     */
    Uni<Boolean> setActive(String versionId);

    /**
     * Delete a config version.
     *
     * <p>Active versions cannot be deleted.
     *
     * @param versionId the version ID to delete
     * @return Uni with true if deleted, false if not found or active
     */
    Uni<Boolean> delete(String versionId);
}
