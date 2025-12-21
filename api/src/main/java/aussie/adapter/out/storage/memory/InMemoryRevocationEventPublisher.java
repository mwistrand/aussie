package aussie.adapter.out.storage.memory;

import java.time.Instant;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import org.jboss.logging.Logger;

import aussie.core.model.auth.RevocationEvent;
import aussie.core.port.out.RevocationEventPublisher;

/**
 * In-memory implementation of RevocationEventPublisher.
 *
 * <p>This implementation is intended for development, testing, and single-instance deployments.
 * Events are broadcast within the same JVM only.
 */
public class InMemoryRevocationEventPublisher implements RevocationEventPublisher {

    private static final Logger LOG = Logger.getLogger(InMemoryRevocationEventPublisher.class);

    private final BroadcastProcessor<RevocationEvent> processor = BroadcastProcessor.create();

    public InMemoryRevocationEventPublisher() {
        LOG.info("Initialized in-memory revocation event publisher");
    }

    @Override
    public Uni<Void> publishJtiRevoked(String jti, Instant expiresAt) {
        return Uni.createFrom().item(() -> {
            var event = new RevocationEvent.JtiRevoked(jti, expiresAt);
            processor.onNext(event);
            LOG.debugf("Published JTI revocation event (in-memory): %s", jti);
            return null;
        });
    }

    @Override
    public Uni<Void> publishUserRevoked(String userId, Instant issuedBefore, Instant expiresAt) {
        return Uni.createFrom().item(() -> {
            var event = new RevocationEvent.UserRevoked(userId, issuedBefore, expiresAt);
            processor.onNext(event);
            LOG.debugf("Published user revocation event (in-memory): %s", userId);
            return null;
        });
    }

    @Override
    public Multi<RevocationEvent> subscribe() {
        return processor;
    }
}
