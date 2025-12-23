package aussie.adapter.out.storage.memory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import aussie.core.port.out.PkceChallengeRepository;
import aussie.spi.PkceStorageProvider;

/**
 * In-memory PKCE storage provider.
 *
 * <p>This provider is always available and serves as a fallback when
 * Redis or other storage backends are unavailable.
 *
 * <p><strong>Warning:</strong> In-memory storage requires sticky sessions
 * when running multiple Aussie instances. Not recommended for production.
 */
@ApplicationScoped
public class InMemoryPkceStorageProvider implements PkceStorageProvider {

    private static final Logger LOG = Logger.getLogger(InMemoryPkceStorageProvider.class);
    private static final int PRIORITY = 0; // Lowest priority - fallback only

    private final AtomicBoolean warningLogged = new AtomicBoolean(false);
    private volatile InMemoryPkceChallengeRepository repository;

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public boolean isAvailable() {
        return true; // Always available
    }

    @Override
    public synchronized PkceChallengeRepository createRepository() {
        if (warningLogged.compareAndSet(false, true)) {
            LOG.warn("========================================================================");
            LOG.warn("  WARNING: PKCE challenge storage is in-memory only!");
            LOG.warn("  Sticky sessions are REQUIRED when running multiple Aussie instances.");
            LOG.warn("  Consider configuring Redis or a custom PkceStorageProvider for production.");
            LOG.warn("========================================================================");
        }

        if (repository == null) {
            repository = new InMemoryPkceChallengeRepository();
        }
        return repository;
    }

    @Override
    public Optional<HealthCheckResponse> healthCheck() {
        return Optional.of(HealthCheckResponse.named("pkce-storage-memory")
                .up()
                .withData("type", "in-memory")
                .withData("challenges", repository != null ? repository.getChallengeCount() : 0)
                .build());
    }
}
