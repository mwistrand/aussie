package aussie.adapter.out.storage.cassandra;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

import aussie.core.model.auth.Role;
import aussie.core.model.auth.RoleMapping;
import aussie.core.model.service.ConditionalWriteResult;
import aussie.core.port.out.RoleRepository;
import aussie.core.service.auth.RoleEncryptionService;

/**
 * Cassandra implementation of RoleRepository.
 *
 * <p>Provides durable, distributed storage for RBAC roles with optional encryption
 * at rest. The role ID is stored separately for efficient lookup, while the
 * full role record is encrypted.
 *
 * <h2>Schema</h2>
 * <pre>
 * CREATE TABLE IF NOT EXISTS roles (
 *     role_id text PRIMARY KEY,
 *     encrypted_data text,
 *     created_at timestamp,
 *     updated_at timestamp
 * );
 * </pre>
 */
public class CassandraRoleRepository implements RoleRepository {

    private final CqlSession session;
    private final RoleEncryptionService encryptionService;
    private final PreparedStatement insertStmt;
    private final PreparedStatement updateIfVersionStmt;
    private final PreparedStatement initializeLegacyVersionStmt;
    private final PreparedStatement selectByIdStmt;
    private final PreparedStatement deleteStmt;
    private final PreparedStatement deleteIfVersionStmt;
    private final PreparedStatement selectAllStmt;
    private final PreparedStatement existsStmt;

    public CassandraRoleRepository(CqlSession session, RoleEncryptionService encryptionService) {
        this.session = session;
        this.encryptionService = encryptionService;
        this.insertStmt = prepareInsert();
        this.updateIfVersionStmt = prepareUpdateIfVersion();
        this.initializeLegacyVersionStmt = prepareInitializeLegacyVersion();
        this.selectByIdStmt = prepareSelectById();
        this.deleteStmt = prepareDelete();
        this.deleteIfVersionStmt = prepareDeleteIfVersion();
        this.selectAllStmt = prepareSelectAll();
        this.existsStmt = prepareExists();
    }

    private PreparedStatement prepareInsert() {
        return session.prepare(
                """
                INSERT INTO roles (role_id, encrypted_data, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?)
                """);
    }

    private PreparedStatement prepareUpdateIfVersion() {
        return session.prepare(
                "UPDATE roles SET encrypted_data = ?, updated_at = ?, version = ? WHERE role_id = ? IF version = ?");
    }

    private PreparedStatement prepareInitializeLegacyVersion() {
        return session.prepare(
                "UPDATE roles SET version = 1 WHERE role_id = ? IF version = NULL AND encrypted_data != NULL");
    }

    private PreparedStatement prepareSelectById() {
        return session.prepare("SELECT * FROM roles WHERE role_id = ?");
    }

    private PreparedStatement prepareDelete() {
        return session.prepare("DELETE FROM roles WHERE role_id = ?");
    }

    private PreparedStatement prepareDeleteIfVersion() {
        return session.prepare("DELETE FROM roles WHERE role_id = ? IF version = ?");
    }

    private PreparedStatement prepareSelectAll() {
        return session.prepare("SELECT * FROM roles");
    }

    private PreparedStatement prepareExists() {
        return session.prepare("SELECT role_id FROM roles WHERE role_id = ?");
    }

    @Override
    public Uni<Void> save(Role role) {
        final Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    final String encryptedData = encryptionService.encrypt(role);
                    final BoundStatement bound = insertStmt.bind(
                            role.id(), encryptedData, role.createdAt(), role.updatedAt(), role.version());
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .replaceWithVoid();
    }

    @Override
    public Uni<ConditionalWriteResult> replaceIfVersion(Role role, long expectedVersion) {
        final var executor = getContextExecutor();
        final var mutation = Uni.createFrom()
                .completionStage(() -> session.executeAsync(updateIfVersionStmt.bind(
                                encryptionService.encrypt(role),
                                role.updatedAt(),
                                role.version(),
                                role.id(),
                                expectedVersion))
                        .toCompletableFuture())
                .emitOn(executor)
                .map(this::conditionalResult);
        return retryAfterLegacyVersionInitialization(role.id(), expectedVersion, mutation);
    }

    @Override
    public Uni<Optional<Role>> findById(String roleId) {
        final Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    final BoundStatement bound = selectByIdStmt.bind(roleId);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .map(rs -> {
                    final Row row = rs.one();
                    return row != null ? Optional.of(fromRow(row)) : Optional.empty();
                });
    }

    @Override
    public Uni<Boolean> delete(String roleId) {
        final Executor executor = getContextExecutor();
        return exists(roleId).flatMap(existed -> {
            if (!existed) {
                return Uni.createFrom().item(false);
            }
            return Uni.createFrom()
                    .completionStage(() -> {
                        final BoundStatement bound = deleteStmt.bind(roleId);
                        return session.executeAsync(bound).toCompletableFuture();
                    })
                    .emitOn(executor)
                    .map(rs -> true);
        });
    }

    @Override
    public Uni<ConditionalWriteResult> deleteIfVersion(String roleId, long expectedVersion) {
        final var executor = getContextExecutor();
        final var mutation = Uni.createFrom()
                .completionStage(() -> session.executeAsync(deleteIfVersionStmt.bind(roleId, expectedVersion))
                        .toCompletableFuture())
                .emitOn(executor)
                .map(this::conditionalResult);
        return retryAfterLegacyVersionInitialization(roleId, expectedVersion, mutation);
    }

    @Override
    public Uni<List<Role>> findAll() {
        final Executor executor = getContextExecutor();
        final var workerExecutor = CassandraPageReader.workerExecutor();
        return Uni.createFrom()
                .completionStage(() -> CassandraPageReader.readAll(
                        session.executeAsync(selectAllStmt.bind()).toCompletableFuture(),
                        this::fromRow,
                        workerExecutor))
                .emitOn(executor);
    }

    @Override
    public Uni<List<Role>> findPage(int limit, int offset) {
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
    public Uni<Boolean> exists(String roleId) {
        final Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    final BoundStatement bound = existsStmt.bind(roleId);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .map(rs -> rs.one() != null);
    }

    @Override
    public Uni<RoleMapping> getRoleMapping() {
        return findAll().map(roles -> {
            final Map<String, Set<String>> mapping =
                    roles.stream().collect(Collectors.toMap(Role::id, Role::permissions));
            return new RoleMapping(mapping);
        });
    }

    private Role fromRow(Row row) {
        final String encryptedData = row.getString("encrypted_data");
        final var role = encryptionService.decrypt(encryptedData);
        return row.isNull("version") ? role : role.withVersion(row.getLong("version"));
    }

    private Uni<ConditionalWriteResult> retryAfterLegacyVersionInitialization(
            String roleId, long expectedVersion, Uni<ConditionalWriteResult> mutation) {
        return mutation.flatMap(result -> {
            if (expectedVersion != 1L
                    || result.applied()
                    || result.currentVersion().isPresent()) {
                return Uni.createFrom().item(result);
            }
            final var executor = getContextExecutor();
            return Uni.createFrom()
                    .completionStage(() -> session.executeAsync(initializeLegacyVersionStmt.bind(roleId))
                            .toCompletableFuture())
                    .emitOn(executor)
                    .map(this::conditionalResult)
                    .flatMap(initialized -> initialized.applied()
                                    || initialized.currentVersion().orElse(-1L) == expectedVersion
                            ? mutation
                            : Uni.createFrom().item(initialized));
        });
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

    /**
     * Get an executor that will run on the Vert.x context if available,
     * otherwise falls back to the default worker pool.
     *
     * <p>This is necessary because the Cassandra driver completes its futures
     * on Netty I/O threads, which don't have a Vert.x context. When Quarkus
     * RESTEasy Reactive tries to resume processing, it expects a Vert.x context.
     */
    private Executor getContextExecutor() {
        final Context context = Vertx.currentContext();
        if (context != null) {
            return command -> context.runOnContext(v -> command.run());
        }
        return Infrastructure.getDefaultWorkerPool();
    }
}
