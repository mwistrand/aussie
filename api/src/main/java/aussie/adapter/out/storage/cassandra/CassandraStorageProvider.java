package aussie.adapter.out.storage.cassandra;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.jboss.logging.Logger;

import aussie.core.model.common.StorageHealth;
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
public class CassandraStorageProvider implements StorageRepositoryProvider, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(CassandraStorageProvider.class);
    private static final String MIGRATION_TIMEOUT_KEY = "aussie.resiliency.cassandra.migration-timeout";
    private static final Duration DEFAULT_MIGRATION_TIMEOUT = Duration.ofSeconds(30);

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
        boolean createKeyspace =
                Boolean.parseBoolean(config.getOrDefault("aussie.storage.cassandra.create-keyspace", "false"));

        if (runMigrations) {
            runMigrations(config, keyspace, createKeyspace);
        }

        final var acquiredSession = buildSession(config);
        try {
            this.session = acquiredSession;
            ObjectMapper objectMapper = new ObjectMapper()
                    .registerModule(new Jdk8Module())
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return new CassandraServiceRegistrationRepository(objectMapper, session);
        } catch (RuntimeException e) {
            this.session = null;
            CassandraSessionRegistry.release(acquiredSession);
            throw e;
        }
    }

    private void runMigrations(StorageAdapterConfig config, String keyspace, boolean createKeyspace) {
        final var context = Vertx.currentContext();
        if (context != null && context.isEventLoopContext()) {
            throw new StorageProviderException("Cassandra migrations cannot run on a Vert.x event loop");
        }

        final var timeout = migrationTimeout(config);
        LOG.infov("Running Cassandra migrations with timeout {0}...", timeout);
        runMigration(
                () -> runMigrationsSynchronously(config, keyspace, createKeyspace),
                timeout,
                Infrastructure.getDefaultWorkerPool());
        LOG.info("Cassandra migrations completed");
    }

    static void runMigration(Runnable operation, Duration timeout, Executor executor) {
        final var migration = new FutureTask<Void>(operation, null);
        executor.execute(migration);
        try {
            migration.get(TimeUnit.NANOSECONDS.convert(timeout), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            migration.cancel(true);
            Thread.currentThread().interrupt();
            throw new StorageProviderException("Cassandra migration interrupted", e);
        } catch (TimeoutException e) {
            migration.cancel(true);
            throw new StorageProviderException("Cassandra migration timed out", e);
        } catch (ExecutionException e) {
            final var cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new StorageProviderException("Cassandra migration failed", cause);
        }
    }

    private void runMigrationsSynchronously(StorageAdapterConfig config, String keyspace, boolean createKeyspace) {
        if (createKeyspace) {
            // Explicitly opt-in for local development; production must supply the keyspace topology.
            try (CqlSession noKeyspaceSession = buildSessionWithoutKeyspace(config)) {
                var keyspaceMigrationRunner = new CassandraMigrationRunner(noKeyspaceSession, keyspace);
                keyspaceMigrationRunner.runKeyspaceMigration();
            }
        }

        CassandraMigrationRunner.checkInterrupted();

        // Then connect with keyspace for all other migrations
        try (CqlSession keyspaceSession = buildSessionInternal(config, keyspace)) {
            var migrationRunner = new CassandraMigrationRunner(keyspaceSession, keyspace);
            migrationRunner.runMigrations();
        }
    }

    private Duration migrationTimeout(StorageAdapterConfig config) {
        try {
            final var timeout = config.getDuration(MIGRATION_TIMEOUT_KEY).orElse(DEFAULT_MIGRATION_TIMEOUT);
            if (timeout.isZero() || timeout.isNegative()) {
                throw new StorageProviderException("Cassandra migration timeout must be positive");
            }
            return timeout;
        } catch (DateTimeParseException e) {
            throw new StorageProviderException("Cassandra migration timeout must be an ISO-8601 duration", e);
        }
    }

    @Override
    public Optional<StorageHealthIndicator> createHealthIndicator(StorageAdapterConfig config) {
        return Optional.of(() -> {
            if (session == null || session.isClosed()) {
                return Uni.createFrom().item(StorageHealth.unhealthy("cassandra", "Session not initialized or closed"));
            }

            Executor executor = getContextExecutor();
            long start = System.currentTimeMillis();
            final var consistency = consistencyLevel(config);
            return Uni.createFrom()
                    .completionStage(() -> session.executeAsync(
                                    SimpleStatement.builder("SELECT release_version FROM system.local")
                                            .setConsistencyLevel(consistency)
                                            .build())
                            .toCompletableFuture())
                    .emitOn(executor)
                    .chain(ignored -> validateProductionTopology(config, consistency))
                    .map(ignored -> {
                        long latency = System.currentTimeMillis() - start;
                        return StorageHealth.healthy("cassandra", latency);
                    })
                    .onFailure()
                    .recoverWithItem(e -> StorageHealth.unhealthy("cassandra", e.getMessage()));
        });
    }

    private Uni<Void> validateProductionTopology(StorageAdapterConfig config, ConsistencyLevel consistency) {
        if (!Boolean.parseBoolean(config.getOrDefault("aussie.production-configuration-validation.enabled", "true"))) {
            return Uni.createFrom().voidItem();
        }

        final var keyspace = config.getOrDefault("aussie.storage.cassandra.keyspace", "aussie");
        final var datacenter = config.getOrDefault("aussie.storage.cassandra.datacenter", "datacenter1");
        final var topology = SimpleStatement.builder(
                        "SELECT replication FROM system_schema.keyspaces WHERE keyspace_name = ?")
                .addPositionalValue(keyspace)
                .setConsistencyLevel(consistency)
                .build();
        return Uni.createFrom()
                .completionStage(() -> session.executeAsync(topology).toCompletableFuture())
                .chain(result -> {
                    final var rows = result.currentPage().iterator();
                    final var row = rows.hasNext() ? rows.next() : null;
                    if (row == null) {
                        return Uni.createFrom()
                                .failure(new IllegalStateException("Cassandra keyspace does not exist: " + keyspace));
                    }
                    try {
                        CassandraTopologyValidator.validate(
                                row.getMap("replication", String.class, String.class), datacenter);
                    } catch (RuntimeException e) {
                        return Uni.createFrom().failure(e);
                    }
                    return validateSchema(result, keyspace, consistency);
                });
    }

    private Uni<Void> validateSchema(
            com.datastax.oss.driver.api.core.cql.AsyncResultSet ignored,
            String keyspace,
            ConsistencyLevel consistency) {
        final var schema = SimpleStatement.builder("SELECT version, status FROM " + keyspace + ".schema_migrations")
                .setConsistencyLevel(consistency)
                .build();
        return Uni.createFrom()
                .completionStage(() -> session.executeAsync(schema).toCompletableFuture())
                .map(result -> {
                    final var expected = CassandraMigrationRunner.expectedSchemaVersion();
                    final var completed = new HashSet<Integer>();
                    for (final var row : result.currentPage()) {
                        if ("COMPLETED".equals(row.getString("status"))) {
                            completed.add(row.getInt("version"));
                        }
                    }
                    for (var version = 2; version <= expected; version++) {
                        if (!completed.contains(version)) {
                            throw new IllegalStateException(
                                    "Cassandra schema is missing completed migration V" + version);
                        }
                    }
                    return (Void) null;
                });
    }

    private CqlSession buildSession(StorageAdapterConfig config) {
        return CassandraSessionRegistry.acquire(config, false);
    }

    private CqlSession buildSessionWithoutKeyspace(StorageAdapterConfig config) {
        return buildSessionInternal(config, null);
    }

    private CqlSession buildSessionInternal(StorageAdapterConfig config, String keyspace) {
        String contactPoints = config.getOrDefault("aussie.storage.cassandra.contact-points", "localhost:9042");
        String datacenter = config.getOrDefault("aussie.storage.cassandra.datacenter", "datacenter1");
        String queryTimeoutStr = config.getOrDefault("aussie.resiliency.cassandra.query-timeout", "PT5S");
        Duration queryTimeout = Duration.parse(queryTimeoutStr);
        final var consistency = consistencyLevel(config);

        // Configure driver with request timeout
        DriverConfigLoader configLoader = DriverConfigLoader.programmaticBuilder()
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, queryTimeout)
                .withString(DefaultDriverOption.REQUEST_CONSISTENCY, consistency.name())
                .build();

        CqlSessionBuilder builder =
                CqlSession.builder().withLocalDatacenter(datacenter).withConfigLoader(configLoader);

        LOG.infov("Cassandra session configured with query timeout: {0}", queryTimeout);

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

    private ConsistencyLevel consistencyLevel(StorageAdapterConfig config) {
        final var configured = config.getOrDefault("aussie.storage.cassandra.consistency-level", "LOCAL_QUORUM");
        try {
            return CassandraTopologyValidator.consistencyLevel(configured);
        } catch (IllegalArgumentException e) {
            throw new StorageProviderException("Invalid Cassandra consistency level: " + configured, e);
        }
    }

    @Override
    public void close() {
        if (session != null) {
            CassandraSessionRegistry.release(session);
            session = null;
        }
    }

    /**
     * Get an executor that will run on the Vert.x context if available,
     * otherwise falls back to the default worker pool.
     */
    private Executor getContextExecutor() {
        Context context = Vertx.currentContext();
        if (context != null) {
            return command -> context.runOnContext(v -> command.run());
        }
        return Infrastructure.getDefaultWorkerPool();
    }
}
