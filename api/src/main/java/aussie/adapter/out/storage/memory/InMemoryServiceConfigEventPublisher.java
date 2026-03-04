package aussie.adapter.out.storage.memory;

import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.DefaultBean;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import org.jboss.logging.Logger;

import aussie.core.model.service.ServiceConfigEvent;
import aussie.core.port.out.ServiceConfigEventPublisher;

/**
 * In-memory implementation of ServiceConfigEventPublisher.
 *
 * <p>This implementation is intended for development, testing, and
 * single-instance deployments. Events are broadcast within the same
 * JVM only.
 *
 * <p>This is the default CDI bean. When a transport-backed implementation
 * (e.g., Redis) is available, it takes precedence automatically.
 */
@ApplicationScoped
@DefaultBean
public class InMemoryServiceConfigEventPublisher implements ServiceConfigEventPublisher {

    private static final Logger LOG = Logger.getLogger(InMemoryServiceConfigEventPublisher.class);

    private final BroadcastProcessor<ServiceConfigEvent> processor = BroadcastProcessor.create();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public InMemoryServiceConfigEventPublisher() {
        LOG.info("Initialized in-memory service config event publisher");
    }

    @PreDestroy
    void close() {
        if (closed.compareAndSet(false, true)) {
            processor.onComplete();
        }
    }

    /** {@inheritDoc} */
    @Override
    public Uni<Void> publishServiceChanged(String serviceId) {
        return Uni.createFrom().voidItem().invoke(() -> {
            if (closed.get()) {
                return;
            }
            var event = new ServiceConfigEvent.ServiceChanged(serviceId);
            processor.onNext(event);
            LOG.debugf("Published service changed event (in-memory): %s", serviceId);
        });
    }

    /** {@inheritDoc} */
    @Override
    public Uni<Void> publishServiceRemoved(String serviceId) {
        return Uni.createFrom().voidItem().invoke(() -> {
            if (closed.get()) {
                return;
            }
            var event = new ServiceConfigEvent.ServiceRemoved(serviceId);
            processor.onNext(event);
            LOG.debugf("Published service removed event (in-memory): %s", serviceId);
        });
    }

    /** {@inheritDoc} */
    @Override
    public Multi<ServiceConfigEvent> subscribe() {
        return processor;
    }
}
