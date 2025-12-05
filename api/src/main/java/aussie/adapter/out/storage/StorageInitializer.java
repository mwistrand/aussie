package aussie.adapter.out.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkus.runtime.StartupEvent;
import org.jboss.logging.Logger;

import aussie.core.service.ServiceRegistry;

/**
 * Initializes the service registry from persistent storage on application startup.
 */
@ApplicationScoped
public class StorageInitializer {

    private static final Logger LOG = Logger.getLogger(StorageInitializer.class);

    private final ServiceRegistry serviceRegistry;

    @Inject
    public StorageInitializer(ServiceRegistry serviceRegistry) {
        this.serviceRegistry = serviceRegistry;
    }

    void onStart(@Observes StartupEvent event) {
        LOG.info("Initializing service registry from persistent storage...");
        serviceRegistry
                .initialize()
                .subscribe()
                .with(
                        v -> LOG.info("Service registry initialized successfully"),
                        e -> LOG.errorf(e, "Failed to initialize service registry"));
    }
}
