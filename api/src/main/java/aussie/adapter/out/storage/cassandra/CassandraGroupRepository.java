package aussie.adapter.out.storage.cassandra;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

import aussie.core.model.auth.Group;
import aussie.core.model.auth.GroupMapping;
import aussie.core.port.out.GroupRepository;
import aussie.core.service.auth.GroupEncryptionService;

/**
 * Cassandra implementation of GroupRepository.
 *
 * <p>Provides durable, distributed storage for RBAC groups with optional encryption
 * at rest. The group ID is stored separately for efficient lookup, while the
 * full group record is encrypted.
 *
 * <h2>Schema</h2>
 * <pre>
 * CREATE TABLE IF NOT EXISTS groups (
 *     group_id text PRIMARY KEY,
 *     encrypted_data text,
 *     created_at timestamp,
 *     updated_at timestamp
 * );
 * </pre>
 */
public class CassandraGroupRepository implements GroupRepository {

    private final CqlSession session;
    private final GroupEncryptionService encryptionService;
    private final PreparedStatement insertStmt;
    private final PreparedStatement selectByIdStmt;
    private final PreparedStatement deleteStmt;
    private final PreparedStatement selectAllStmt;
    private final PreparedStatement existsStmt;

    public CassandraGroupRepository(CqlSession session, GroupEncryptionService encryptionService) {
        this.session = session;
        this.encryptionService = encryptionService;
        this.insertStmt = prepareInsert();
        this.selectByIdStmt = prepareSelectById();
        this.deleteStmt = prepareDelete();
        this.selectAllStmt = prepareSelectAll();
        this.existsStmt = prepareExists();
    }

    private PreparedStatement prepareInsert() {
        return session.prepare(
                """
                INSERT INTO groups (group_id, encrypted_data, created_at, updated_at)
                VALUES (?, ?, toTimestamp(now()), toTimestamp(now()))
                """);
    }

    private PreparedStatement prepareSelectById() {
        return session.prepare("SELECT * FROM groups WHERE group_id = ?");
    }

    private PreparedStatement prepareDelete() {
        return session.prepare("DELETE FROM groups WHERE group_id = ?");
    }

    private PreparedStatement prepareSelectAll() {
        return session.prepare("SELECT * FROM groups");
    }

    private PreparedStatement prepareExists() {
        return session.prepare("SELECT group_id FROM groups WHERE group_id = ?");
    }

    @Override
    public Uni<Void> save(Group group) {
        final Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    final String encryptedData = encryptionService.encrypt(group);
                    final BoundStatement bound = insertStmt.bind(group.id(), encryptedData);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .replaceWithVoid();
    }

    @Override
    public Uni<Optional<Group>> findById(String groupId) {
        final Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    final BoundStatement bound = selectByIdStmt.bind(groupId);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .map(rs -> {
                    final Row row = rs.one();
                    return row != null ? Optional.of(fromRow(row)) : Optional.empty();
                });
    }

    @Override
    public Uni<Boolean> delete(String groupId) {
        final Executor executor = getContextExecutor();
        return exists(groupId).flatMap(existed -> {
            if (!existed) {
                return Uni.createFrom().item(false);
            }
            return Uni.createFrom()
                    .completionStage(() -> {
                        final BoundStatement bound = deleteStmt.bind(groupId);
                        return session.executeAsync(bound).toCompletableFuture();
                    })
                    .emitOn(executor)
                    .map(rs -> true);
        });
    }

    @Override
    public Uni<List<Group>> findAll() {
        final Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(
                        () -> session.executeAsync(selectAllStmt.bind()).toCompletableFuture())
                .emitOn(executor)
                .map(rs -> {
                    final List<Group> groups = new ArrayList<>();
                    rs.currentPage().forEach(row -> groups.add(fromRow(row)));
                    return groups;
                });
    }

    @Override
    public Uni<Boolean> exists(String groupId) {
        final Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    final BoundStatement bound = existsStmt.bind(groupId);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .map(rs -> rs.one() != null);
    }

    @Override
    public Uni<GroupMapping> getGroupMapping() {
        return findAll().map(groups -> {
            final Map<String, Set<String>> mapping =
                    groups.stream().collect(Collectors.toMap(Group::id, Group::permissions));
            return new GroupMapping(mapping);
        });
    }

    private Group fromRow(Row row) {
        final String encryptedData = row.getString("encrypted_data");
        return encryptionService.decrypt(encryptedData);
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
