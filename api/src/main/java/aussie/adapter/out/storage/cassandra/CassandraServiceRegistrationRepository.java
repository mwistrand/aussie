package aussie.adapter.out.storage.cassandra;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
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
import aussie.core.model.auth.VisibilityRule;
import aussie.core.model.common.CorsConfig;
import aussie.core.model.common.ServiceSecurityHeadersConfig;
import aussie.core.model.ratelimit.ServiceRateLimitConfig;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.sampling.ServiceSamplingConfig;
import aussie.core.model.service.ConditionalWriteResult;
import aussie.core.model.service.ServiceRegistration;
import aussie.core.model.timeout.ServiceTimeoutConfig;
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
    private final PreparedStatement insertIfAbsentStmt;
    private final PreparedStatement updateIfVersionStmt;
    private final PreparedStatement initializeLegacyVersionStmt;
    private final PreparedStatement selectByIdStmt;
    private final PreparedStatement deleteStmt;
    private final PreparedStatement deleteIfVersionStmt;
    private final PreparedStatement selectAllStmt;
    private final PreparedStatement countStmt;
    private final PreparedStatement existsStmt;
    private final PreparedStatement selectGenerationStmt;
    private final PreparedStatement initializeGenerationStmt;
    private final PreparedStatement updateGenerationStmt;

    public CassandraServiceRegistrationRepository(ObjectMapper objectMapper, CqlSession session) {
        this.objectMapper = objectMapper;
        this.session = session;
        this.insertStmt = prepareInsert();
        this.insertIfAbsentStmt = prepareInsertIfAbsent();
        this.updateIfVersionStmt = prepareUpdateIfVersion();
        this.initializeLegacyVersionStmt = prepareInitializeLegacyVersion();
        this.selectByIdStmt = prepareSelectById();
        this.deleteStmt = prepareDelete();
        this.deleteIfVersionStmt = prepareDeleteIfVersion();
        this.selectAllStmt = prepareSelectAll();
        this.countStmt = prepareCount();
        this.existsStmt = prepareExists();
        this.selectGenerationStmt =
                session.prepare("SELECT generation FROM service_config_generation WHERE scope = 'global'");
        this.initializeGenerationStmt = session.prepare(
                "INSERT INTO service_config_generation (scope, generation, updated_at) VALUES ('global', 1, toTimestamp(now())) IF NOT EXISTS");
        this.updateGenerationStmt = session.prepare(
                "UPDATE service_config_generation SET generation = ?, updated_at = toTimestamp(now()) WHERE scope = 'global' IF generation = ?");
    }

    private PreparedStatement prepareInsert() {
        return session.prepare(
                """
                        INSERT INTO service_registrations
                        (service_id, display_name, base_url, route_prefix,
                         default_visibility, default_auth_required, visibility_rules, endpoints, access_config,
                         cors_config, permission_policy, rate_limit_config, sampling_config, security_headers_config,
                         timeout_config, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, toTimestamp(now()), toTimestamp(now()))
                        """);
    }

    private PreparedStatement prepareInsertIfAbsent() {
        return session.prepare(
                """
                        INSERT INTO service_registrations
                        (service_id, display_name, base_url, route_prefix,
                         default_visibility, default_auth_required, visibility_rules, endpoints, access_config,
                         cors_config, permission_policy, rate_limit_config, sampling_config, security_headers_config,
                         timeout_config, version, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, toTimestamp(now()), toTimestamp(now()))
                        IF NOT EXISTS
                        """);
    }

    private PreparedStatement prepareUpdateIfVersion() {
        return session.prepare(
                """
                        UPDATE service_registrations SET
                        display_name = ?, base_url = ?, route_prefix = ?, default_visibility = ?,
                        default_auth_required = ?, visibility_rules = ?, endpoints = ?, access_config = ?,
                        cors_config = ?, permission_policy = ?, rate_limit_config = ?, sampling_config = ?,
                        security_headers_config = ?, timeout_config = ?, version = ?, updated_at = toTimestamp(now())
                        WHERE service_id = ? IF version = ?
                        """);
    }

    private PreparedStatement prepareInitializeLegacyVersion() {
        return session.prepare(
                """
                        UPDATE service_registrations SET version = 1
                        WHERE service_id = ? IF version = NULL AND display_name != NULL
                        """);
    }

    private PreparedStatement prepareSelectById() {
        return session.prepare("SELECT * FROM service_registrations WHERE service_id = ?");
    }

    private PreparedStatement prepareDelete() {
        return session.prepare("DELETE FROM service_registrations WHERE service_id = ?");
    }

    private PreparedStatement prepareDeleteIfVersion() {
        return session.prepare("DELETE FROM service_registrations WHERE service_id = ? IF version = ?");
    }

    private PreparedStatement prepareSelectAll() {
        return session.prepare("SELECT * FROM service_registrations");
    }

    private PreparedStatement prepareCount() {
        return session.prepare("SELECT service_id FROM service_registrations");
    }

    private PreparedStatement prepareExists() {
        return session.prepare("SELECT service_id FROM service_registrations WHERE service_id = ?");
    }

    @Override
    public Uni<Void> save(ServiceRegistration registration) {
        Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> session.executeAsync(bindInsert(insertStmt, registration))
                        .toCompletableFuture())
                .emitOn(executor)
                .call(ignored -> advanceGeneration())
                .replaceWithVoid();
    }

    @Override
    public Uni<ConditionalWriteResult> createIfAbsent(ServiceRegistration registration) {
        final var executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> session.executeAsync(bindInsert(insertIfAbsentStmt, registration))
                        .toCompletableFuture())
                .emitOn(executor)
                .map(this::createResult)
                .call(result -> result.applied()
                        ? advanceGeneration()
                        : Uni.createFrom().voidItem());
    }

    @Override
    public Uni<ConditionalWriteResult> replaceIfVersion(ServiceRegistration registration, long expectedVersion) {
        final var executor = getContextExecutor();
        final var mutation = Uni.createFrom()
                .completionStage(() -> {
                    final var bound = updateIfVersionStmt.bind(
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
                            registration.samplingConfig().map(this::toJson).orElse(null),
                            registration
                                    .securityHeadersConfig()
                                    .map(this::toJson)
                                    .orElse(null),
                            registration.timeoutConfig().map(this::toJson).orElse(null),
                            registration.version(),
                            registration.serviceId(),
                            expectedVersion);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .map(this::conditionalResult);
        return retryAfterLegacyVersionInitialization(registration.serviceId(), expectedVersion, mutation)
                .call(result -> result.applied()
                        ? advanceGeneration()
                        : Uni.createFrom().voidItem());
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
                    .call(ignored -> advanceGeneration())
                    .map(rs -> true);
        });
    }

    @Override
    public Uni<ConditionalWriteResult> deleteIfVersion(String serviceId, long expectedVersion) {
        final var executor = getContextExecutor();
        final var mutation = Uni.createFrom()
                .completionStage(() -> session.executeAsync(deleteIfVersionStmt.bind(serviceId, expectedVersion))
                        .toCompletableFuture())
                .emitOn(executor)
                .map(this::conditionalResult);
        return retryAfterLegacyVersionInitialization(serviceId, expectedVersion, mutation)
                .call(result -> result.applied()
                        ? advanceGeneration()
                        : Uni.createFrom().voidItem());
    }

    @Override
    public Uni<List<ServiceRegistration>> findAll() {
        Executor executor = getContextExecutor();
        final var workerExecutor = CassandraPageReader.workerExecutor();
        return Uni.createFrom()
                .completionStage(() -> CassandraPageReader.readAll(
                        session.executeAsync(selectAllStmt.bind()).toCompletableFuture(),
                        this::fromRow,
                        workerExecutor))
                .emitOn(executor);
    }

    @Override
    public Uni<List<ServiceRegistration>> findPage(int limit, int offset) {
        final var executor = getContextExecutor();
        final var workerExecutor = CassandraPageReader.workerExecutor();
        final var statement = selectAllStmt.bind().setPageSize(limit);
        return Uni.createFrom()
                .completionStage(() -> CassandraPageReader.readPage(
                        session.executeAsync(statement).toCompletableFuture(),
                        limit,
                        offset,
                        this::fromRow,
                        workerExecutor))
                .emitOn(executor);
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
        final var workerExecutor = CassandraPageReader.workerExecutor();
        return Uni.createFrom()
                .completionStage(() -> CassandraPageReader.countAll(
                        session.executeAsync(countStmt.bind()).toCompletableFuture(), workerExecutor))
                .emitOn(executor);
    }

    @Override
    public Uni<Long> currentGeneration() {
        final var executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(
                        () -> session.executeAsync(selectGenerationStmt.bind()).toCompletableFuture())
                .emitOn(executor)
                .map(result -> {
                    final var row = result.one();
                    return row == null ? 0L : row.getLong("generation");
                });
    }

    private Uni<Void> advanceGeneration() {
        return currentGeneration().flatMap(current -> {
            final var executor = getContextExecutor();
            return Uni.createFrom()
                    .completionStage(() -> session.executeAsync(updateGenerationStmt.bind(current + 1L, current))
                            .toCompletableFuture())
                    .emitOn(executor)
                    .flatMap(result -> {
                        if (result.wasApplied()) {
                            return Uni.createFrom().voidItem();
                        }
                        if (current != 0L) {
                            return advanceGeneration();
                        }
                        return Uni.createFrom()
                                .completionStage(() -> session.executeAsync(initializeGenerationStmt.bind())
                                        .toCompletableFuture())
                                .emitOn(executor)
                                .flatMap(initialized -> initialized.wasApplied()
                                        ? Uni.createFrom().voidItem()
                                        : advanceGeneration());
                    });
        });
    }

    private BoundStatement bindInsert(PreparedStatement statement, ServiceRegistration registration) {
        return statement.bind(
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
                registration.samplingConfig().map(this::toJson).orElse(null),
                registration.securityHeadersConfig().map(this::toJson).orElse(null),
                registration.timeoutConfig().map(this::toJson).orElse(null),
                registration.version());
    }

    private Uni<ConditionalWriteResult> retryAfterLegacyVersionInitialization(
            String serviceId, long expectedVersion, Uni<ConditionalWriteResult> mutation) {
        return mutation.flatMap(result -> {
            if (expectedVersion != 1L
                    || result.applied()
                    || result.currentVersion().isPresent()) {
                return Uni.createFrom().item(result);
            }
            final var executor = getContextExecutor();
            return Uni.createFrom()
                    .completionStage(() -> session.executeAsync(initializeLegacyVersionStmt.bind(serviceId))
                            .toCompletableFuture())
                    .emitOn(executor)
                    .map(this::conditionalResult)
                    .flatMap(initialized -> initialized.applied()
                                    || initialized.currentVersion().orElse(-1L) == expectedVersion
                            ? mutation
                            : Uni.createFrom().item(initialized));
        });
    }

    private ConditionalWriteResult createResult(AsyncResultSet resultSet) {
        final var result = conditionalResult(resultSet);
        return result.applied() || result.currentVersion().isPresent() ? result : ConditionalWriteResult.rejected(1L);
    }

    private ConditionalWriteResult conditionalResult(AsyncResultSet resultSet) {
        if (resultSet.wasApplied()) {
            return ConditionalWriteResult.appliedResult();
        }
        final var row = resultSet.one();
        return row == null || row.isNull("version")
                ? ConditionalWriteResult.missing()
                : ConditionalWriteResult.rejected(row.getLong("version"));
    }

    private ServiceRegistration fromRow(Row row) {
        // Default to true for existing rows where column is null
        boolean defaultAuthRequired =
                row.isNull("default_auth_required") ? true : row.getBoolean("default_auth_required");

        // Default to 1 for existing rows where version is null
        long version = row.isNull("version") ? 1L : row.getLong("version");

        return new ServiceRegistration(
                row.getString("service_id"),
                row.getString("display_name"),
                URI.create(row.getString("base_url")),
                row.getString("route_prefix"),
                EndpointVisibility.valueOf(row.getString("default_visibility")),
                defaultAuthRequired,
                fromJsonList(row.getString("visibility_rules"), new TypeReference<List<VisibilityRule>>() {}),
                fromJsonList(row.getString("endpoints"), new TypeReference<List<EndpointConfig>>() {}),
                Optional.ofNullable(row.getString("access_config"))
                        .map(json -> fromJson(json, ServiceAccessConfig.class)),
                Optional.ofNullable(row.getString("cors_config")).map(json -> fromJson(json, CorsConfig.class)),
                Optional.ofNullable(row.getString("permission_policy"))
                        .map(json -> fromJson(json, ServicePermissionPolicy.class)),
                Optional.ofNullable(row.getString("rate_limit_config"))
                        .map(json -> fromJson(json, ServiceRateLimitConfig.class)),
                Optional.ofNullable(row.isNull("sampling_config") ? null : row.getString("sampling_config"))
                        .map(json -> fromJson(json, ServiceSamplingConfig.class)),
                Optional.ofNullable(
                                row.isNull("security_headers_config") ? null : row.getString("security_headers_config"))
                        .map(json -> fromJson(json, ServiceSecurityHeadersConfig.class)),
                Optional.ofNullable(row.isNull("timeout_config") ? null : row.getString("timeout_config"))
                        .map(json -> fromJson(json, ServiceTimeoutConfig.class)),
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
     * Get an executor that will run on the Vert.x context if available,
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
