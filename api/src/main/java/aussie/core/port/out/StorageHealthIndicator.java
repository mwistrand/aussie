package aussie.core.port.out;

import io.smallrye.mutiny.Uni;

import aussie.core.model.StorageHealth;

/**
 * Optional interface for storage health checks.
 *
 * <p>Providers may implement this to expose health status via /q/health endpoints.
 */
public interface StorageHealthIndicator {

    /**
     * Check if the storage backend is healthy.
     *
     * @return Uni with health status
     */
    Uni<StorageHealth> check();
}
