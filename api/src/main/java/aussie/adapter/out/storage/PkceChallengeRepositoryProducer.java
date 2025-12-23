package aussie.adapter.out.storage;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import aussie.core.port.out.PkceChallengeRepository;
import aussie.core.service.auth.PkceStorageProviderRegistry;

/**
 * CDI producer for PKCE challenge repository.
 *
 * <p>Delegates to the {@link PkceStorageProviderRegistry} which discovers and
 * selects the appropriate storage provider based on configuration and availability.
 *
 * <p>Platform teams can provide custom implementations by implementing
 * {@link aussie.spi.PkceStorageProvider} and registering it via CDI.
 *
 * @see aussie.spi.PkceStorageProvider
 */
@ApplicationScoped
public class PkceChallengeRepositoryProducer {

    private final PkceStorageProviderRegistry registry;

    @Inject
    public PkceChallengeRepositoryProducer(PkceStorageProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * Produces a PKCE challenge repository from the selected storage provider.
     *
     * @return PKCE challenge repository from the registry-selected provider
     */
    @Produces
    @ApplicationScoped
    public PkceChallengeRepository pkceChallengeRepository() {
        return registry.getRepository();
    }
}
