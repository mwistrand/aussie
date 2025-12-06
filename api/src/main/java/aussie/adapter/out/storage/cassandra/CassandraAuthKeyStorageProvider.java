package aussie.adapter.out.storage.cassandra;

import java.net.InetSocketAddress;
import java.util.Optional;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import io.smallrye.mutiny.Uni;

import aussie.core.model.StorageHealth;
import aussie.core.port.out.ApiKeyRepository;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.core.service.ApiKeyEncryptionService;
import aussie.spi.AuthKeyStorageProvider;
import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;

/**
 * Cassandra storage provider for API keys.
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>aussie.auth.storage.cassandra.contact-points - Comma-separated host:port pairs (default: localhost:9042)</li>
 *   <li>aussie.auth.storage.cassandra.datacenter - Local datacenter name (default: datacenter1)</li>
 *   <li>aussie.auth.storage.cassandra.keyspace - Keyspace name (default: aussie)</li>
 *   <li>aussie.auth.storage.cassandra.username - Username for authentication (optional)</li>
 *   <li>aussie.auth.storage.cassandra.password - Password for authentication (optional)</li>
 * </ul>
 *
 * <p>This provider shares the same Cassandra cluster as the main storage provider
 * but uses separate configuration keys under aussie.auth.storage.* namespace.
 * If auth-specific config is not provided, it falls back to aussie.storage.cassandra.* config.
 */
public class CassandraAuthKeyStorageProvider implements AuthKeyStorageProvider {

    private CqlSession session;
    private ApiKeyEncryptionService encryptionService;

    @Override
    public String name() {
        return "cassandra";
    }

    @Override
    public String description() {
        return "Apache Cassandra persistent storage for API keys";
    }

    @Override
    public int priority() {
        return 10;
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
    public ApiKeyRepository createRepository(StorageAdapterConfig config) {
        this.session = buildSession(config);
        this.encryptionService = createEncryptionService(config);
        return new CassandraApiKeyRepository(session, encryptionService);
    }

    @Override
    public Optional<StorageHealthIndicator> createHealthIndicator(StorageAdapterConfig config) {
        return Optional.of(() -> {
            if (session == null || session.isClosed()) {
                return Uni.createFrom()
                        .item(StorageHealth.unhealthy("cassandra-auth", "Session not initialized or closed"));
            }

            long start = System.currentTimeMillis();
            return Uni.createFrom()
                    .completionStage(() -> session.executeAsync("SELECT release_version FROM system.local")
                            .toCompletableFuture())
                    .map(rs -> {
                        long latency = System.currentTimeMillis() - start;
                        return StorageHealth.healthy("cassandra-auth", latency);
                    })
                    .onFailure()
                    .recoverWithItem(e -> StorageHealth.unhealthy("cassandra-auth", e.getMessage()));
        });
    }

    private CqlSession buildSession(StorageAdapterConfig config) {
        // Try auth-specific config first, fall back to general storage config
        String contactPoints = config.get("aussie.auth.storage.cassandra.contact-points")
                .or(() -> config.get("aussie.storage.cassandra.contact-points"))
                .orElse("localhost:9042");
        String datacenter = config.get("aussie.auth.storage.cassandra.datacenter")
                .or(() -> config.get("aussie.storage.cassandra.datacenter"))
                .orElse("datacenter1");
        String keyspace = config.get("aussie.auth.storage.cassandra.keyspace")
                .or(() -> config.get("aussie.storage.cassandra.keyspace"))
                .orElse("aussie");

        CqlSessionBuilder builder =
                CqlSession.builder().withLocalDatacenter(datacenter).withKeyspace(keyspace);

        for (String contactPoint : contactPoints.split(",")) {
            String[] parts = contactPoint.trim().split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 9042;
            builder.addContactPoint(new InetSocketAddress(host, port));
        }

        // Check auth-specific credentials first, then fall back
        Optional<String> username = config.get("aussie.auth.storage.cassandra.username")
                .or(() -> config.get("aussie.storage.cassandra.username"));

        username.ifPresent(u -> {
            String password = config.get("aussie.auth.storage.cassandra.password")
                    .or(() -> config.get("aussie.storage.cassandra.password"))
                    .orElseThrow(() ->
                            new StorageProviderException("Cassandra password required when username is specified"));
            builder.withAuthCredentials(u, password);
        });

        try {
            return builder.build();
        } catch (Exception e) {
            throw new StorageProviderException("Failed to connect to Cassandra for auth storage", e);
        }
    }

    private ApiKeyEncryptionService createEncryptionService(StorageAdapterConfig config) {
        Optional<String> encryptionKey = config.get("aussie.auth.encryption.key");
        String keyId = config.getOrDefault("aussie.auth.encryption.key-id", "v1");
        return new ApiKeyEncryptionService(encryptionKey, keyId);
    }
}
