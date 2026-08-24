package aussie.adapter.out.storage.cassandra;

import java.util.Optional;
import java.util.concurrent.Executor;

import com.datastax.oss.driver.api.core.CqlSession;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Vertx;
import org.jboss.logging.Logger;

import aussie.core.model.common.StorageHealth;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.core.port.out.TranslationConfigRepository;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.TranslationConfigStorageProvider;

/**
 * Cassandra storage provider for translation configurations.
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>aussie.storage.cassandra.contact-points - Comma-separated host:port pairs (default: localhost:9042)</li>
 *   <li>aussie.storage.cassandra.datacenter - Local datacenter name (default: datacenter1)</li>
 *   <li>aussie.storage.cassandra.keyspace - Keyspace name (default: aussie)</li>
 *   <li>aussie.storage.cassandra.username - Username for authentication (optional)</li>
 *   <li>aussie.storage.cassandra.password - Password for authentication (optional)</li>
 * </ul>
 */
public class CassandraTranslationConfigStorageProvider implements TranslationConfigStorageProvider, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(CassandraTranslationConfigStorageProvider.class);

    private CqlSession session;

    @Override
    public String name() {
        return "cassandra";
    }

    @Override
    public String description() {
        return "Apache Cassandra persistent storage for translation configs";
    }

    @Override
    public int priority() {
        return 10; // Higher than memory
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("com.datastax.oss.driver.api.core.CqlSession");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public TranslationConfigRepository createRepository(StorageAdapterConfig config) {
        final var acquiredSession = buildSession(config);
        try {
            this.session = acquiredSession;
            LOG.info("Created Cassandra translation config repository");
            return new CassandraTranslationConfigRepository(session);
        } catch (RuntimeException e) {
            this.session = null;
            CassandraSessionRegistry.release(acquiredSession);
            throw e;
        }
    }

    @Override
    public void close() {
        if (session != null) {
            LOG.info("Releasing Cassandra session for translation config storage");
            CassandraSessionRegistry.release(session);
            session = null;
        }
    }

    @Override
    public Optional<StorageHealthIndicator> createHealthIndicator(StorageAdapterConfig config) {
        return Optional.of(() -> {
            if (session == null || session.isClosed()) {
                return Uni.createFrom()
                        .item(StorageHealth.unhealthy(
                                "cassandra-translation-config", "Session not initialized or closed"));
            }

            final var executor = getContextExecutor();
            final var start = System.currentTimeMillis();
            return Uni.createFrom()
                    .completionStage(() -> session.executeAsync("SELECT release_version FROM system.local")
                            .toCompletableFuture())
                    .emitOn(executor)
                    .map(rs -> {
                        final var latency = System.currentTimeMillis() - start;
                        return StorageHealth.healthy("cassandra-translation-config", latency);
                    })
                    .onFailure()
                    .recoverWithItem(e -> StorageHealth.unhealthy("cassandra-translation-config", e.getMessage()));
        });
    }

    private CqlSession buildSession(StorageAdapterConfig config) {
        return CassandraSessionRegistry.acquire(config, false);
    }

    private Executor getContextExecutor() {
        final var context = Vertx.currentContext();
        if (context != null) {
            return command -> context.runOnContext(v -> command.run());
        }
        return Infrastructure.getDefaultWorkerPool();
    }
}
