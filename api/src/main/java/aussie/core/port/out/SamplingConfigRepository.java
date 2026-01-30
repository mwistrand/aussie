package aussie.core.port.out;

import java.util.Optional;

import io.smallrye.mutiny.Uni;

import aussie.core.model.sampling.ServiceSamplingConfig;

/**
 * Port interface for sampling configuration storage.
 *
 * <p>Implementations may use any backing store: in-memory, database, cache, etc.
 * The default in-memory implementation is suitable for development or
 * single-instance deployments where sampling configs are managed externally.
 *
 * <p>Platform teams can provide custom implementations via the
 * {@link aussie.spi.SamplingConfigProvider} SPI to integrate with their
 * existing service registry or configuration management systems.
 *
 * <h2>Thread Safety</h2>
 * <p>Implementations must be thread-safe as they are called concurrently
 * from the OTel sampler during request processing.
 */
public interface SamplingConfigRepository {

    /**
     * Look up sampling configuration for a service.
     *
     * <p>Returns the service-level sampling configuration if one is defined
     * for the given service ID. Returns empty if no configuration exists,
     * in which case the platform default rate will be used.
     *
     * @param serviceId the service ID to look up
     * @return Uni with Optional containing the sampling config if found
     */
    Uni<Optional<ServiceSamplingConfig>> findByServiceId(String serviceId);
}
