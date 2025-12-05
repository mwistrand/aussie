package aussie.adapter.out.storage.cassandra;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;

import aussie.core.model.EndpointVisibility;
import aussie.core.model.ServiceAccessConfig;
import aussie.core.model.ServiceRegistration;
import aussie.core.port.out.ServiceRegistrationRepository;

/**
 * Cassandra implementation of ServiceRegistrationRepository.
 *
 * <p>Provides durable, distributed storage for service registrations.
 * Complex nested structures are stored as JSON in text columns.
 */
public class CassandraServiceRegistrationRepository implements ServiceRegistrationRepository {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final CqlSession session;
    private final PreparedStatement insertStmt;
    private final PreparedStatement selectByIdStmt;
    private final PreparedStatement deleteStmt;
    private final PreparedStatement selectAllStmt;
    private final PreparedStatement countStmt;
    private final PreparedStatement existsStmt;

    public CassandraServiceRegistrationRepository(CqlSession session) {
        this.session = session;
        this.insertStmt = prepareInsert();
        this.selectByIdStmt = prepareSelectById();
        this.deleteStmt = prepareDelete();
        this.selectAllStmt = prepareSelectAll();
        this.countStmt = prepareCount();
        this.existsStmt = prepareExists();
    }

    private PreparedStatement prepareInsert() {
        return session.prepare(
                """
                INSERT INTO service_registrations
                (service_id, display_name, base_url, route_prefix,
                 default_visibility, visibility_rules, endpoints, access_config,
                 created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, toTimestamp(now()), toTimestamp(now()))
                """);
    }

    private PreparedStatement prepareSelectById() {
        return session.prepare("SELECT * FROM service_registrations WHERE service_id = ?");
    }

    private PreparedStatement prepareDelete() {
        return session.prepare("DELETE FROM service_registrations WHERE service_id = ?");
    }

    private PreparedStatement prepareSelectAll() {
        return session.prepare("SELECT * FROM service_registrations");
    }

    private PreparedStatement prepareCount() {
        return session.prepare("SELECT COUNT(*) FROM service_registrations");
    }

    private PreparedStatement prepareExists() {
        return session.prepare("SELECT service_id FROM service_registrations WHERE service_id = ?");
    }

    @Override
    public Uni<Void> save(ServiceRegistration registration) {
        return Uni.createFrom()
                .completionStage(() -> {
                    BoundStatement bound = insertStmt.bind(
                            registration.serviceId(),
                            registration.displayName(),
                            registration.baseUrl().toString(),
                            registration.routePrefix(),
                            registration.defaultVisibility().name(),
                            toJson(registration.visibilityRules()),
                            toJson(registration.endpoints()),
                            registration.accessConfig().map(this::toJson).orElse(null));
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .replaceWithVoid();
    }

    @Override
    public Uni<Optional<ServiceRegistration>> findById(String serviceId) {
        return Uni.createFrom()
                .completionStage(() -> {
                    BoundStatement bound = selectByIdStmt.bind(serviceId);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .map(rs -> {
                    Row row = rs.one();
                    return row != null ? Optional.of(fromRow(row)) : Optional.empty();
                });
    }

    @Override
    public Uni<Boolean> delete(String serviceId) {
        return exists(serviceId).flatMap(existed -> {
            if (!existed) {
                return Uni.createFrom().item(false);
            }
            return Uni.createFrom()
                    .completionStage(() -> {
                        BoundStatement bound = deleteStmt.bind(serviceId);
                        return session.executeAsync(bound).toCompletableFuture();
                    })
                    .map(rs -> true);
        });
    }

    @Override
    public Uni<List<ServiceRegistration>> findAll() {
        return Uni.createFrom()
                .completionStage(
                        () -> session.executeAsync(selectAllStmt.bind()).toCompletableFuture())
                .map(rs -> {
                    List<ServiceRegistration> registrations = new ArrayList<>();
                    rs.currentPage().forEach(row -> registrations.add(fromRow(row)));
                    return registrations;
                });
    }

    @Override
    public Uni<Boolean> exists(String serviceId) {
        return Uni.createFrom()
                .completionStage(() -> {
                    BoundStatement bound = existsStmt.bind(serviceId);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .map(rs -> rs.one() != null);
    }

    @Override
    public Uni<Long> count() {
        return Uni.createFrom()
                .completionStage(() -> session.executeAsync(countStmt.bind()).toCompletableFuture())
                .map(rs -> {
                    Row row = rs.one();
                    return row != null ? row.getLong(0) : 0L;
                });
    }

    private ServiceRegistration fromRow(Row row) {
        return new ServiceRegistration(
                row.getString("service_id"),
                row.getString("display_name"),
                URI.create(row.getString("base_url")),
                row.getString("route_prefix"),
                EndpointVisibility.valueOf(row.getString("default_visibility")),
                fromJsonList(row.getString("visibility_rules"), new TypeReference<>() {}),
                fromJsonList(row.getString("endpoints"), new TypeReference<>() {}),
                Optional.ofNullable(row.getString("access_config"))
                        .map(json -> fromJson(json, ServiceAccessConfig.class)));
    }

    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return OBJECT_MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize from JSON", e);
        }
    }

    private <T> List<T> fromJsonList(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize list from JSON", e);
        }
    }
}
