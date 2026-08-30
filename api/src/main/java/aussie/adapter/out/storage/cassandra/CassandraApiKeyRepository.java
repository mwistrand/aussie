package aussie.adapter.out.storage.cassandra;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BatchStatement;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.DefaultBatchType;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

import aussie.core.model.auth.ApiKey;
import aussie.core.model.service.ConditionalWriteResult;
import aussie.core.port.out.ApiKeyRepository;
import aussie.core.service.auth.ApiKeyEncryptionService;

/**
 * Cassandra implementation of ApiKeyRepository.
 *
 * <p>Provides durable, distributed storage for API keys with optional encryption
 * at rest. The key hash is stored separately for efficient lookup, while the
 * full API key record is encrypted.
 *
 * <h2>Schema</h2>
 * <pre>
 * CREATE TABLE IF NOT EXISTS api_keys (
 *     key_id text PRIMARY KEY,
 *     key_hash text,
 *     encrypted_data text,
 *     created_at timestamp,
 *     updated_at timestamp
 * );
 * CREATE TABLE IF NOT EXISTS api_keys_by_hash (
 *     key_hash text PRIMARY KEY,
 *     key_id text,
 *     encrypted_data text,
 *     created_at timestamp,
 *     updated_at timestamp
 * );
 * </pre>
 */
public class CassandraApiKeyRepository implements ApiKeyRepository {

    private final CqlSession session;
    private final ApiKeyEncryptionService encryptionService;
    private final PreparedStatement insertStmt;
    private final PreparedStatement updateIfVersionStmt;
    private final PreparedStatement initializeLegacyVersionStmt;
    private final PreparedStatement insertByHashStmt;
    private final PreparedStatement selectByIdStmt;
    private final PreparedStatement selectByHashStmt;
    private final PreparedStatement selectLegacyByHashStmt;
    private final PreparedStatement deleteStmt;
    private final PreparedStatement deleteIfVersionStmt;
    private final PreparedStatement deleteByHashStmt;
    private final PreparedStatement selectAllStmt;
    private final PreparedStatement existsStmt;

    public CassandraApiKeyRepository(CqlSession session, ApiKeyEncryptionService encryptionService) {
        this.session = session;
        this.encryptionService = encryptionService;
        this.insertStmt = prepareInsert();
        this.updateIfVersionStmt = prepareUpdateIfVersion();
        this.initializeLegacyVersionStmt = prepareInitializeLegacyVersion();
        this.insertByHashStmt = prepareInsertByHash();
        this.selectByIdStmt = prepareSelectById();
        this.selectByHashStmt = prepareSelectByHash();
        this.selectLegacyByHashStmt = prepareSelectLegacyByHash();
        this.deleteStmt = prepareDelete();
        this.deleteIfVersionStmt = prepareDeleteIfVersion();
        this.deleteByHashStmt = prepareDeleteByHash();
        this.selectAllStmt = prepareSelectAll();
        this.existsStmt = prepareExists();
    }

    private PreparedStatement prepareInsert() {
        return session.prepare("""
                INSERT INTO api_keys (key_id, key_hash, encrypted_data, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?)
                """);
    }

    private PreparedStatement prepareUpdateIfVersion() {
        return session.prepare(
                "UPDATE api_keys SET encrypted_data = ?, updated_at = toTimestamp(now()), version = ? WHERE key_id = ? IF version = ?");
    }

    private PreparedStatement prepareInitializeLegacyVersion() {
        return session.prepare(
                "UPDATE api_keys SET version = 1 WHERE key_id = ? IF version = NULL AND encrypted_data != NULL");
    }

    private PreparedStatement prepareInsertByHash() {
        return session.prepare("""
                INSERT INTO api_keys_by_hash (key_hash, key_id, encrypted_data, created_at, updated_at)
                VALUES (?, ?, ?, toTimestamp(now()), toTimestamp(now()))
                """);
    }

    private PreparedStatement prepareSelectById() {
        return session.prepare("SELECT * FROM api_keys WHERE key_id = ?");
    }

    private PreparedStatement prepareSelectByHash() {
        return session.prepare("SELECT * FROM api_keys_by_hash WHERE key_hash = ?");
    }

    private PreparedStatement prepareSelectLegacyByHash() {
        return session.prepare("SELECT * FROM api_keys WHERE key_hash = ?");
    }

    private PreparedStatement prepareDelete() {
        return session.prepare("DELETE FROM api_keys WHERE key_id = ?");
    }

    private PreparedStatement prepareDeleteIfVersion() {
        return session.prepare("DELETE FROM api_keys WHERE key_id = ? IF version = ?");
    }

    private PreparedStatement prepareDeleteByHash() {
        return session.prepare("DELETE FROM api_keys_by_hash WHERE key_hash = ?");
    }

    private PreparedStatement prepareSelectAll() {
        return session.prepare("SELECT * FROM api_keys");
    }

    private PreparedStatement prepareExists() {
        return session.prepare("SELECT key_id FROM api_keys WHERE key_id = ?");
    }

    @Override
    public Uni<Void> save(ApiKey apiKey) {
        Executor executor = getContextExecutor();
        return findById(apiKey.id())
                .flatMap(existing -> Uni.createFrom().completionStage(() -> {
                    final var encryptedData = encryptionService.encrypt(apiKey);
                    final var batch = BatchStatement.builder(DefaultBatchType.LOGGED)
                            .addStatement(insertStmt.bind(
                                    apiKey.id(),
                                    apiKey.keyHash(),
                                    encryptedData,
                                    apiKey.createdAt(),
                                    apiKey.createdAt(),
                                    apiKey.version()))
                            .addStatement(insertByHashStmt.bind(apiKey.keyHash(), apiKey.id(), encryptedData));
                    existing.filter(previous -> !previous.keyHash().equals(apiKey.keyHash()))
                            .ifPresent(previous -> batch.addStatement(deleteByHashStmt.bind(previous.keyHash())));
                    return session.executeAsync(batch.build()).toCompletableFuture();
                }))
                .emitOn(executor)
                .replaceWithVoid();
    }

    @Override
    public Uni<ConditionalWriteResult> replaceIfVersion(ApiKey apiKey, long expectedVersion) {
        final var executor = getContextExecutor();
        final var mutation = Uni.createFrom()
                .completionStage(() -> session.executeAsync(updateIfVersionStmt.bind(
                                encryptionService.encrypt(apiKey), apiKey.version(), apiKey.id(), expectedVersion))
                        .toCompletableFuture())
                .emitOn(executor)
                .map(this::conditionalResult);
        return retryAfterLegacyVersionInitialization(apiKey.id(), expectedVersion, mutation);
    }

    @Override
    public Uni<Optional<ApiKey>> findById(String keyId) {
        Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    BoundStatement bound = selectByIdStmt.bind(keyId);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .map(rs -> {
                    Row row = rs.one();
                    return row != null ? Optional.of(fromVersionedRow(row)) : Optional.empty();
                });
    }

    @Override
    public Uni<Optional<ApiKey>> findByHash(String keyHash) {
        return findByHash(selectByHashStmt, keyHash)
                .flatMap(indexed -> indexed.isEmpty()
                        ? findByHash(selectLegacyByHashStmt, keyHash)
                        : findById(indexed.get().id())
                                .flatMap(current -> current.filter(key -> keyHash.equals(key.keyHash()))
                                                .isPresent()
                                        ? Uni.createFrom().item(current)
                                        : findByHash(selectLegacyByHashStmt, keyHash)));
    }

    private Uni<Optional<ApiKey>> findByHash(PreparedStatement statement, String keyHash) {
        Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    BoundStatement bound = statement.bind(keyHash);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .map(rs -> {
                    Row row = rs.one();
                    return row != null
                            ? Optional.of(fromRow(row)).filter(key -> keyHash.equals(key.keyHash()))
                            : Optional.empty();
                });
    }

    @Override
    public Uni<Boolean> delete(String keyId) {
        Executor executor = getContextExecutor();
        return findById(keyId).flatMap(existing -> {
            if (existing.isEmpty()) {
                return Uni.createFrom().item(false);
            }
            final var apiKey = existing.get();
            return Uni.createFrom()
                    .completionStage(() -> {
                        final var batch = BatchStatement.builder(DefaultBatchType.LOGGED)
                                .addStatement(deleteStmt.bind(keyId))
                                .addStatement(deleteByHashStmt.bind(apiKey.keyHash()))
                                .build();
                        return session.executeAsync(batch).toCompletableFuture();
                    })
                    .emitOn(executor)
                    .map(rs -> true);
        });
    }

    @Override
    public Uni<ConditionalWriteResult> deleteIfVersion(String keyId, long expectedVersion) {
        final var executor = getContextExecutor();
        final var mutation = Uni.createFrom()
                .completionStage(() -> session.executeAsync(deleteIfVersionStmt.bind(keyId, expectedVersion))
                        .toCompletableFuture())
                .emitOn(executor)
                .map(this::conditionalResult);
        return retryAfterLegacyVersionInitialization(keyId, expectedVersion, mutation);
    }

    @Override
    public Uni<List<ApiKey>> findAll() {
        Executor executor = getContextExecutor();
        final var workerExecutor = CassandraPageReader.workerExecutor();
        return Uni.createFrom()
                .completionStage(() -> CassandraPageReader.readAll(
                        session.executeAsync(selectAllStmt.bind()).toCompletableFuture(),
                        this::fromVersionedRow,
                        workerExecutor))
                .emitOn(executor);
    }

    @Override
    public Uni<List<ApiKey>> findPage(int limit, int offset) {
        final var executor = getContextExecutor();
        final var workerExecutor = CassandraPageReader.workerExecutor();
        final var statement = selectAllStmt.bind().setPageSize(limit);
        return Uni.createFrom()
                .completionStage(() -> CassandraPageReader.readPage(
                        session.executeAsync(statement).toCompletableFuture(),
                        limit,
                        offset,
                        this::fromVersionedRow,
                        workerExecutor))
                .emitOn(executor);
    }

    @Override
    public Uni<Boolean> exists(String keyId) {
        Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    BoundStatement bound = existsStmt.bind(keyId);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .map(rs -> rs.one() != null);
    }

    private ApiKey fromRow(Row row) {
        String encryptedData = row.getString("encrypted_data");
        return encryptionService.decrypt(encryptedData);
    }

    private ApiKey fromVersionedRow(Row row) {
        final var apiKey = fromRow(row);
        return row.isNull("version") ? apiKey : apiKey.withVersion(row.getLong("version"));
    }

    private Uni<ConditionalWriteResult> retryAfterLegacyVersionInitialization(
            String keyId, long expectedVersion, Uni<ConditionalWriteResult> mutation) {
        return mutation.flatMap(result -> {
            if (expectedVersion != 1L
                    || result.applied()
                    || result.currentVersion().isPresent()) {
                return Uni.createFrom().item(result);
            }
            final var executor = getContextExecutor();
            return Uni.createFrom()
                    .completionStage(() -> session.executeAsync(initializeLegacyVersionStmt.bind(keyId))
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
        Context context = Vertx.currentContext();
        if (context != null) {
            return command -> context.runOnContext(v -> command.run());
        }
        return Infrastructure.getDefaultWorkerPool();
    }
}
