package aussie.adapter.out.storage.cassandra;

import java.net.InetSocketAddress;
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
import aussie.spi.StorageProviderException;
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
        this.session = buildSession(config);
        LOG.info("Created Cassandra translation config repository");
        return new CassandraTranslationConfigRepository(session);
    }

    @Override
    public void close() {
        if (session != null && !session.isClosed()) {
            LOG.info("Closing Cassandra session for translation config storage");
            session.close();
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
        final var contactPoints = config.getOrDefault("aussie.storage.cassandra.contact-points", "localhost:9042");
        final var datacenter = config.getOrDefault("aussie.storage.cassandra.datacenter", "datacenter1");
        final var keyspace = config.getOrDefault("aussie.storage.cassandra.keyspace", "aussie");

        final var builder = CqlSession.builder().withLocalDatacenter(datacenter).withKeyspace(keyspace);

        for (String contactPoint : contactPoints.split(",")) {
            final var parts = contactPoint.trim().split(":");
            final var host = parts[0];
            final var port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9042;
            builder.addContactPoint(new InetSocketAddress(host, port));
        }

        config.get("aussie.storage.cassandra.username").ifPresent(username -> {
            final var password = config.get("aussie.storage.cassandra.password")
                    .orElseThrow(() ->
                            new StorageProviderException("Cassandra password required when username is specified"));
            builder.withAuthCredentials(username, password);
        });

        try {
            return builder.build();
        } catch (Exception e) {
            throw new StorageProviderException("Failed to connect to Cassandra for translation config storage", e);
        }
    }

    private Executor getContextExecutor() {
        final var context = Vertx.currentContext();
        if (context != null) {
            return command -> context.runOnContext(v -> command.run());
        }
        return Infrastructure.getDefaultWorkerPool();
    }
}
