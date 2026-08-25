package aussie.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.datastax.oss.driver.api.core.CqlSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.storage.cassandra.CassandraMigrationRunner;
import aussie.e2e.support.SuiteContext;

@DisplayName("Cassandra migration compatibility")
final class CassandraMigrationE2ETest {

    @Test
    @DisplayName("packaged artifact applies the latest checksummed schema and reruns cleanly")
    void packagedArtifactAppliesLatestSchema() {
        final var ctx = SuiteContext.get();
        try (var session = session(ctx, "aussie")) {
            final var rows = session.execute("SELECT version, status, checksum FROM schema_migrations")
                    .all();
            final var expectedVersions = IntStream.rangeClosed(2, 19).boxed().collect(Collectors.toSet());

            assertEquals(
                    expectedVersions,
                    rows.stream().map(row -> row.getInt("version")).collect(Collectors.toSet()));
            rows.forEach(row -> {
                assertEquals("COMPLETED", row.getString("status"));
                assertNotNull(row.getString("checksum"));
            });
            assertEquals(0, new CassandraMigrationRunner(session, "aussie").runMigrations());
        }
    }

    @Test
    @DisplayName("the full manifest works with a non-default keyspace")
    void supportsCustomKeyspace() {
        final var ctx = SuiteContext.get();
        final var keyspace = "aussie_e2e_" + UUID.randomUUID().toString().replace("-", "");
        try (var clusterSession = session(ctx, null)) {
            new CassandraMigrationRunner(clusterSession, keyspace).runKeyspaceMigration();
            try (var keyspaceSession = session(ctx, keyspace)) {
                assertEquals(18, new CassandraMigrationRunner(keyspaceSession, keyspace).runMigrations());
                final var versions = keyspaceSession.execute("SELECT version FROM schema_migrations").all().stream()
                        .map(row -> row.getInt("version"))
                        .collect(Collectors.toSet());
                assertEquals(IntStream.rangeClosed(2, 19).boxed().collect(Collectors.toSet()), versions);
            }
        } finally {
            try (var clusterSession = session(ctx, null)) {
                clusterSession.execute("DROP KEYSPACE IF EXISTS " + keyspace);
            }
        }
    }

    private static CqlSession session(SuiteContext ctx, String keyspace) {
        final var builder = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(ctx.cassandraHost(), ctx.cassandraPort()))
                .withLocalDatacenter("datacenter1");
        if (keyspace != null) {
            builder.withKeyspace(keyspace);
        }
        return builder.build();
    }
}
