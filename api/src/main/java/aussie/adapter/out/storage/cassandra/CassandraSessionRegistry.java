package aussie.adapter.out.storage.cassandra;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;

import aussie.spi.StorageAdapterConfig;
import aussie.spi.StorageProviderException;

/** Owns one Cassandra session for each distinct application configuration. */
final class CassandraSessionRegistry {

    private static final Map<SessionKey, Entry> SESSIONS = new HashMap<>();

    private CassandraSessionRegistry() {}

    static synchronized CqlSession acquire(StorageAdapterConfig config, boolean authNamespace) {
        final var key = SessionKey.from(config, authNamespace);
        var entry = SESSIONS.get(key);
        if (entry == null) {
            entry = new Entry(buildSession(key));
            SESSIONS.put(key, entry);
        }
        entry.references++;
        return entry.session;
    }

    static synchronized void release(CqlSession session) {
        for (final var iterator = SESSIONS.entrySet().iterator(); iterator.hasNext(); ) {
            final var entry = iterator.next().getValue();
            if (entry.session != session) {
                continue;
            }
            if (--entry.references == 0) {
                iterator.remove();
                session.close();
            }
            return;
        }
    }

    private static CqlSession buildSession(SessionKey key) {
        final var configLoader = DriverConfigLoader.programmaticBuilder()
                .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, key.queryTimeout)
                .build();
        final var builder = CqlSession.builder()
                .withLocalDatacenter(key.datacenter)
                .withKeyspace(key.keyspace)
                .withConfigLoader(configLoader);
        for (final var contactPoint : key.contactPoints.split(",")) {
            final var parts = contactPoint.trim().split(":", 2);
            builder.addContactPoint(
                    new InetSocketAddress(parts[0], parts.length == 2 ? Integer.parseInt(parts[1]) : 9042));
        }
        if (key.username != null) {
            builder.withAuthCredentials(key.username, key.password);
        }
        try {
            return builder.build();
        } catch (Exception e) {
            throw new StorageProviderException("Failed to connect to Cassandra", e);
        }
    }

    private record SessionKey(
            String contactPoints,
            String datacenter,
            String keyspace,
            String username,
            String password,
            Duration queryTimeout) {

        static SessionKey from(StorageAdapterConfig config, boolean authNamespace) {
            final var prefix = authNamespace ? "aussie.auth.storage.cassandra." : "aussie.storage.cassandra.";
            final var fallback = "aussie.storage.cassandra.";
            final var contactPoints =
                    value(config, prefix + "contact-points", fallback + "contact-points", "localhost:9042");
            final var datacenter = value(config, prefix + "datacenter", fallback + "datacenter", "datacenter1");
            final var keyspace = value(config, prefix + "keyspace", fallback + "keyspace", "aussie");
            final var username = value(config, prefix + "username", fallback + "username", null);
            final var password =
                    username == null ? null : value(config, prefix + "password", fallback + "password", null);
            if (username != null && password == null) {
                throw new StorageProviderException("Cassandra password required when username is specified");
            }
            final var timeout = Duration.parse(
                    config.get("aussie.resiliency.cassandra.query-timeout").orElse("PT5S"));
            return new SessionKey(contactPoints, datacenter, keyspace, username, password, timeout);
        }

        private static String value(StorageAdapterConfig config, String primary, String fallback, String defaultValue) {
            return config.get(primary).or(() -> config.get(fallback)).orElse(defaultValue);
        }
    }

    private static final class Entry {
        private final CqlSession session;
        private int references;

        private Entry(CqlSession session) {
            this.session = session;
        }
    }
}
