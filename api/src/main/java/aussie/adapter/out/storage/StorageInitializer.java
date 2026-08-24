package aussie.adapter.out.storage;

import java.time.Duration;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.TimeoutException;
import org.jboss.logging.Logger;

import aussie.core.service.lifecycle.StartupState;
import aussie.core.service.routing.ServiceRegistry;

/**
 * Initialize the service registry from persistent storage on application startup.
 */
@ApplicationScoped
public class StorageInitializer {

    private static final Logger LOG = Logger.getLogger(StorageInitializer.class);
    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(30);

    private final ServiceRegistry serviceRegistry;
    private final StartupState startupState;

    @Inject
    public StorageInitializer(ServiceRegistry serviceRegistry, StartupState startupState) {
        this.serviceRegistry = serviceRegistry;
        this.startupState = startupState;
    }

    void onStart(@Observes @Priority(1) StartupEvent event) {
        LOG.info("Initializing service registry from persistent storage...");
        try {
            serviceRegistry.initialize().await().atMost(STARTUP_TIMEOUT);
            startupState.complete(StartupState.Phase.DEPENDENCIES_CONNECTED);
            startupState.complete(StartupState.Phase.SNAPSHOT_LOADED);
            LOG.info("Service registry initialized successfully");
        } catch (TimeoutException e) {
            startupState.fail(StartupState.Failure.ROUTE_SNAPSHOT_UNAVAILABLE);
            throw new IllegalStateException("Route snapshot initialization timed out", e);
        } catch (RuntimeException e) {
            startupState.fail(StartupState.Failure.ROUTE_SNAPSHOT_UNAVAILABLE);
            throw new IllegalStateException("Route snapshot initialization failed", e);
        }
    }
}
