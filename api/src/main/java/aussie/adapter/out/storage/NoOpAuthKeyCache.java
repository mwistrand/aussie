package aussie.adapter.out.storage;

import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.ApiKey;
import aussie.core.port.out.AuthKeyCache;

/**
 * No-op implementation of AuthKeyCache.
 *
 * <p>Used when caching is disabled or no cache provider is available.
 * All operations succeed but don't actually cache anything.
 */
public class NoOpAuthKeyCache implements AuthKeyCache {

    public static final NoOpAuthKeyCache INSTANCE = new NoOpAuthKeyCache();

    private NoOpAuthKeyCache() {}

    @Override
    public Uni<Optional<ApiKey>> get(String keyHash) {
        return Uni.createFrom().item(Optional.empty());
    }

    @Override
    public Uni<Void> put(String keyHash, ApiKey apiKey) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> invalidate(String keyHash) {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> invalidateAll() {
        return Uni.createFrom().voidItem();
    }
}
