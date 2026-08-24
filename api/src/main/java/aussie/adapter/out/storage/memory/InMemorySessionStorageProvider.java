package aussie.adapter.out.storage.memory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import aussie.core.port.out.SessionRepository;
import aussie.spi.SessionStorageProvider;

/**
 * In-memory session storage provider.
 *
 * <p>This provider is always available and serves as a fallback when
 * Redis or other storage backends are unavailable.
 *
 * <p><strong>Warning:</strong> In-memory storage requires sticky sessions
 * when running multiple Aussie instances. Not recommended for production.
 */
@ApplicationScoped
public class InMemorySessionStorageProvider implements SessionStorageProvider, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(InMemorySessionStorageProvider.class);
    private static final int PRIORITY = 0; // Lowest priority - fallback only

    private final AtomicBoolean warningLogged = new AtomicBoolean(false);
    private volatile InMemorySessionRepository repository;

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
    public synchronized SessionRepository createRepository() {
        if (warningLogged.compareAndSet(false, true)) {
            LOG.warn("========================================================================");
            LOG.warn("  WARNING: Session storage is in-memory only!");
            LOG.warn("  Sticky sessions are REQUIRED when running multiple Aussie instances.");
            LOG.warn("  Consider configuring Redis or a custom SessionStorageProvider for production.");
            LOG.warn("========================================================================");
        }

        if (repository == null) {
            repository = new InMemorySessionRepository();
        }
        return repository;
    }

    @Override
    public Optional<HealthCheckResponse> healthCheck() {
        final var currentRepository = repository;
        return Optional.of(HealthCheckResponse.named("session-storage-memory")
                .up()
                .withData("type", "in-memory")
                .withData("sessions", currentRepository != null ? currentRepository.getSessionCount() : 0)
                .build());
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        if (repository != null) {
            repository.shutdown();
            repository = null;
        }
    }
}
