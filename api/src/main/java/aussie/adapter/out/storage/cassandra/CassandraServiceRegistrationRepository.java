package aussie.adapter.out.storage.cassandra;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

import aussie.core.model.auth.ServiceAccessConfig;
import aussie.core.model.auth.ServicePermissionPolicy;
import aussie.core.model.common.CorsConfig;
import aussie.core.model.ratelimit.ServiceRateLimitConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.port.out.ServiceRegistrationRepository;

/**
 * Cassandra implementation of ServiceRegistrationRepository.
 *
 * <p>
 * Provides durable, distributed storage for service registrations.
 * Complex nested structures are stored as JSON in text columns.
 */
public class CassandraServiceRegistrationRepository implements ServiceRegistrationRepository {

    private final ObjectMapper objectMapper;
    private final CqlSession session;
    private final PreparedStatement insertStmt;
    private final PreparedStatement selectByIdStmt;
    private final PreparedStatement deleteStmt;
    private final PreparedStatement selectAllStmt;
    private final PreparedStatement countStmt;
    private final PreparedStatement existsStmt;

    public CassandraServiceRegistrationRepository(ObjectMapper objectMapper, CqlSession session) {
        this.objectMapper = objectMapper;
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
                         default_visibility, default_auth_required, visibility_rules, endpoints, access_config,
                         cors_config, permission_policy, rate_limit_config, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, toTimestamp(now()), toTimestamp(now()))
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
        Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    BoundStatement bound = insertStmt.bind(
                            registration.serviceId(),
                            registration.displayName(),
                            registration.baseUrl().toString(),
                            registration.routePrefix(),
                            registration.defaultVisibility().name(),
                            registration.defaultAuthRequired(),
                            toJson(registration.visibilityRules()),
                            toJson(registration.endpoints()),
                            registration.accessConfig().map(this::toJson).orElse(null),
                            registration.corsConfig().map(this::toJson).orElse(null),
                            registration.permissionPolicy().map(this::toJson).orElse(null),
                            registration.rateLimitConfig().map(this::toJson).orElse(null),
                            registration.version());
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .replaceWithVoid();
    }

    @Override
    public Uni<Optional<ServiceRegistration>> findById(String serviceId) {
        Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    BoundStatement bound = selectByIdStmt.bind(serviceId);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .map(rs -> {
                    Row row = rs.one();
                    return row != null ? Optional.of(fromRow(row)) : Optional.empty();
                });
    }

    @Override
    public Uni<Boolean> delete(String serviceId) {
        Executor executor = getContextExecutor();
        return exists(serviceId).flatMap(existed -> {
            if (!existed) {
                return Uni.createFrom().item(false);
            }
            return Uni.createFrom()
                    .completionStage(() -> {
                        BoundStatement bound = deleteStmt.bind(serviceId);
                        return session.executeAsync(bound).toCompletableFuture();
                    })
                    .emitOn(executor)
                    .map(rs -> true);
        });
    }

    @Override
    public Uni<List<ServiceRegistration>> findAll() {
        Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(
                        () -> session.executeAsync(selectAllStmt.bind()).toCompletableFuture())
                .emitOn(executor)
                .map(rs -> {
                    List<ServiceRegistration> registrations = new ArrayList<>();
                    rs.currentPage().forEach(row -> registrations.add(fromRow(row)));
                    return registrations;
                });
    }

    @Override
    public Uni<Boolean> exists(String serviceId) {
        Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    BoundStatement bound = existsStmt.bind(serviceId);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .map(rs -> rs.one() != null);
    }

    @Override
    public Uni<Long> count() {
        Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> session.executeAsync(countStmt.bind()).toCompletableFuture())
                .emitOn(executor)
                .map(rs -> {
                    Row row = rs.one();
                    return row != null ? row.getLong(0) : 0L;
                });
    }

    private ServiceRegistration fromRow(Row row) {
        // Default to true for existing rows where column is null
        boolean defaultAuthRequired = row.isNull("default_auth_required") ? true : row.getBool("default_auth_required");

        // Default to 1 for existing rows where version is null
        long version = row.isNull("version") ? 1L : row.getLong("version");

        return new ServiceRegistration(
                row.getString("service_id"),
                row.getString("display_name"),
                URI.create(row.getString("base_url")),
                row.getString("route_prefix"),
                EndpointVisibility.valueOf(row.getString("default_visibility")),
                defaultAuthRequired,
                fromJsonList(row.getString("visibility_rules"), new TypeReference<>() {}),
                fromJsonList(row.getString("endpoints"), new TypeReference<>() {}),
                Optional.ofNullable(row.getString("access_config"))
                        .map(json -> fromJson(json, ServiceAccessConfig.class)),
                Optional.ofNullable(row.getString("cors_config")).map(json -> fromJson(json, CorsConfig.class)),
                Optional.ofNullable(row.getString("permission_policy"))
                        .map(json -> fromJson(json, ServicePermissionPolicy.class)),
                Optional.ofNullable(row.getString("rate_limit_config"))
                        .map(json -> fromJson(json, ServiceRateLimitConfig.class)),
                version);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize from JSON", e);
        }
    }

    private <T> List<T> fromJsonList(String json, TypeReference<List<T>> typeRef) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize list from JSON", e);
        }
    }

    /**
     * Gets an executor that will run on the Vert.x context if available,
     * otherwise falls back to the default worker pool.
     *
     * <p>
     * This is necessary because the Cassandra driver completes its futures
     * on Netty I/O threads, which don't have a Vert.x context. When Quarkus
     * RESTEasy Reactive tries to resume processing, it expects a Vert.x context.
     */
    private Executor getContextExecutor() {
        Context context = Vertx.currentContext();
        if (context != null) {
            return command -> context.runOnContext(v -> command.run());
        }
        return Infrastructure.getDefaultWorkerPool();
    }
}
