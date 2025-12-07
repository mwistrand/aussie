package aussie.adapter.out.storage.cassandra;

import java.net.InetSocketAddress;
import java.util.Optional;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import io.smallrye.mutiny.Uni;
import org.jboss.logging.Logger;

import aussie.core.model.StorageHealth;
import aussie.core.port.out.ServiceRegistrationRepository;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;
import aussie.spi.StorageRepositoryProvider;

/**
 * Cassandra storage provider.
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
public class CassandraStorageProvider implements StorageRepositoryProvider {

    private static final Logger LOG = Logger.getLogger(CassandraStorageProvider.class);

    private CqlSession session;

    @Override
    public String name() {
        return "cassandra";
    }

    @Override
    public String description() {
        return "Apache Cassandra persistent storage";
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
    public ServiceRegistrationRepository createRepository(StorageAdapterConfig config) {
        String keyspace = config.getOrDefault("aussie.storage.cassandra.keyspace", "aussie");
        boolean runMigrations =
                Boolean.parseBoolean(config.getOrDefault("aussie.storage.cassandra.run-migrations", "false"));

        if (runMigrations) {
            runMigrations(config, keyspace);
        }

        this.session = buildSession(config);
        return new CassandraServiceRegistrationRepository(session);
    }

    private void runMigrations(StorageAdapterConfig config, String keyspace) {
        LOG.info("Running Cassandra migrations...");

        // First, connect without keyspace to run keyspace creation migration
        try (CqlSession noKeyspaceSession = buildSessionWithoutKeyspace(config)) {
            // Run V1 (keyspace creation) with session without keyspace
            var keyspaceMigrationRunner = new CassandraMigrationRunner(noKeyspaceSession, keyspace);
            keyspaceMigrationRunner.runKeyspaceMigration();
        } catch (Exception e) {
            LOG.warnv("Keyspace migration check failed (may already exist): {0}", e.getMessage());
        }

        // Then connect with keyspace for all other migrations
        try (CqlSession keyspaceSession = buildSession(config)) {
            var migrationRunner = new CassandraMigrationRunner(keyspaceSession, keyspace);
            migrationRunner.runMigrations();
        }

        LOG.info("Cassandra migrations completed");
    }

    @Override
    public Optional<StorageHealthIndicator> createHealthIndicator(StorageAdapterConfig config) {
        return Optional.of(() -> {
            if (session == null || session.isClosed()) {
                return Uni.createFrom().item(StorageHealth.unhealthy("cassandra", "Session not initialized or closed"));
            }

            long start = System.currentTimeMillis();
            return Uni.createFrom()
                    .completionStage(() -> session.executeAsync("SELECT release_version FROM system.local")
                            .toCompletableFuture())
                    .map(rs -> {
                        long latency = System.currentTimeMillis() - start;
                        return StorageHealth.healthy("cassandra", latency);
                    })
                    .onFailure()
                    .recoverWithItem(e -> StorageHealth.unhealthy("cassandra", e.getMessage()));
        });
    }

    private CqlSession buildSession(StorageAdapterConfig config) {
        String keyspace = config.getOrDefault("aussie.storage.cassandra.keyspace", "aussie");
        return buildSessionInternal(config, keyspace);
    }

    private CqlSession buildSessionWithoutKeyspace(StorageAdapterConfig config) {
        return buildSessionInternal(config, null);
    }

    private CqlSession buildSessionInternal(StorageAdapterConfig config, String keyspace) {
        String contactPoints = config.getOrDefault("aussie.storage.cassandra.contact-points", "localhost:9042");
        String datacenter = config.getOrDefault("aussie.storage.cassandra.datacenter", "datacenter1");

        CqlSessionBuilder builder = CqlSession.builder().withLocalDatacenter(datacenter);

        if (keyspace != null) {
            builder.withKeyspace(keyspace);
        }

        for (String contactPoint : contactPoints.split(",")) {
            String[] parts = contactPoint.trim().split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9042;
            builder.addContactPoint(new InetSocketAddress(host, port));
        }

        config.get("aussie.storage.cassandra.username").ifPresent(username -> {
            String password = config.get("aussie.storage.cassandra.password")
                    .orElseThrow(() ->
                            new StorageProviderException("Cassandra password required when username is specified"));
            builder.withAuthCredentials(username, password);
        });

        try {
            return builder.build();
        } catch (Exception e) {
            throw new StorageProviderException("Failed to connect to Cassandra", e);
        }
    }
}
