package aussie.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import com.datastax.oss.driver.api.core.CqlSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.storage.cassandra.CassandraServiceRegistrationRepository;
import aussie.e2e.support.SuiteContext;

@DisplayName("Packaged Cassandra paging")
final class CassandraPagingE2ETest {

    @Test
    @DisplayName("maps a requested slice across Cassandra driver pages")
    void mapsSliceAcrossDriverPages() {
        final var context = SuiteContext.get();
        final var prefix = "paging-e2e-" + UUID.randomUUID().toString().replace("-", "");
        final var ids = IntStream.range(0, 201)
                .mapToObj(index -> prefix + String.format("-%03d", index))
                .toList();

        try (var session = session(context)) {
            insertServices(session, ids);
            final var repository = new CassandraServiceRegistrationRepository(new ObjectMapper(), session);

            final var page = repository.findPage(100, 100).await().atMost(Duration.ofSeconds(30));
            final var pageIds =
                    page.stream().map(service -> service.serviceId()).toList();

            assertEquals(100, pageIds.size());
            assertEquals(new HashSet<>(ids.subList(100, 200)), new HashSet<>(pageIds));
        } finally {
            try (var session = session(context)) {
                deleteServices(session, ids);
            }
        }
    }

    private static CqlSession session(SuiteContext context) {
        return CqlSession.builder()
                .addContactPoint(new InetSocketAddress(context.cassandraHost(), context.cassandraPort()))
                .withLocalDatacenter("datacenter1")
                .withKeyspace("aussie")
                .build();
    }

    private static void insertServices(CqlSession session, List<String> ids) {
        final var statement =
                session.prepare("INSERT INTO service_registrations (service_id, display_name, base_url, route_prefix, "
                        + "default_visibility, default_auth_required, visibility_rules, endpoints, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
        ids.forEach(id ->
                session.execute(statement.bind(id, id, "http://demo:3000", "/" + id, "PUBLIC", false, "[]", "[]", 1L)));
    }

    private static void deleteServices(CqlSession session, List<String> ids) {
        final var statement = session.prepare("DELETE FROM service_registrations WHERE service_id = ?");
        ids.forEach(id -> session.execute(statement.bind(id)));
    }
}
