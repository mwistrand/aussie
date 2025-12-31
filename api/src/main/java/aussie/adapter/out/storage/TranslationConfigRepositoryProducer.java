package aussie.adapter.out.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import org.jboss.logging.Logger;

import aussie.adapter.out.storage.memory.InMemoryTranslationConfigRepository;

/**
 * CDI producer for TranslationConfigRepository.
 *
 * <p>Currently produces an in-memory implementation. For production use with
 * persistence, replace with a Cassandra or other persistent implementation.
 */
@ApplicationScoped
public class TranslationConfigRepositoryProducer {

    private static final Logger LOG = Logger.getLogger(TranslationConfigRepositoryProducer.class);

    @Produces
    @ApplicationScoped
    public InMemoryTranslationConfigRepository translationConfigRepository() {
        LOG.info("Creating in-memory translation config repository");
        return new InMemoryTranslationConfigRepository();
    }
}
