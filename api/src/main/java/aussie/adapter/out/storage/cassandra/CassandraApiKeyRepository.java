package aussie.adapter.out.storage.cassandra;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import io.vertx.core.Vertx;

import aussie.core.model.auth.ApiKey;
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
 * CREATE INDEX IF NOT EXISTS api_keys_hash_idx ON api_keys (key_hash);
 * </pre>
 */
public class CassandraApiKeyRepository implements ApiKeyRepository {

    private final CqlSession session;
    private final ApiKeyEncryptionService encryptionService;
    private final PreparedStatement insertStmt;
    private final PreparedStatement selectByIdStmt;
    private final PreparedStatement selectByHashStmt;
    private final PreparedStatement deleteStmt;
    private final PreparedStatement selectAllStmt;
    private final PreparedStatement existsStmt;

    public CassandraApiKeyRepository(CqlSession session, ApiKeyEncryptionService encryptionService) {
        this.session = session;
        this.encryptionService = encryptionService;
        this.insertStmt = prepareInsert();
        this.selectByIdStmt = prepareSelectById();
        this.selectByHashStmt = prepareSelectByHash();
        this.deleteStmt = prepareDelete();
        this.selectAllStmt = prepareSelectAll();
        this.existsStmt = prepareExists();
    }

    private PreparedStatement prepareInsert() {
        return session.prepare(
                """
                INSERT INTO api_keys (key_id, key_hash, encrypted_data, created_at, updated_at)
                VALUES (?, ?, ?, toTimestamp(now()), toTimestamp(now()))
                """);
    }

    private PreparedStatement prepareSelectById() {
        return session.prepare("SELECT * FROM api_keys WHERE key_id = ?");
    }

    private PreparedStatement prepareSelectByHash() {
        return session.prepare("SELECT * FROM api_keys WHERE key_hash = ?");
    }

    private PreparedStatement prepareDelete() {
        return session.prepare("DELETE FROM api_keys WHERE key_id = ?");
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
        return Uni.createFrom()
                .completionStage(() -> {
                    String encryptedData = encryptionService.encrypt(apiKey);
                    BoundStatement bound = insertStmt.bind(apiKey.id(), apiKey.keyHash(), encryptedData);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .replaceWithVoid();
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
                    return row != null ? Optional.of(fromRow(row)) : Optional.empty();
                });
    }

    @Override
    public Uni<Optional<ApiKey>> findByHash(String keyHash) {
        Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    BoundStatement bound = selectByHashStmt.bind(keyHash);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .map(rs -> {
                    Row row = rs.one();
                    return row != null ? Optional.of(fromRow(row)) : Optional.empty();
                });
    }

    @Override
    public Uni<Boolean> delete(String keyId) {
        Executor executor = getContextExecutor();
        return exists(keyId).flatMap(existed -> {
            if (!existed) {
                return Uni.createFrom().item(false);
            }
            return Uni.createFrom()
                    .completionStage(() -> {
                        BoundStatement bound = deleteStmt.bind(keyId);
                        return session.executeAsync(bound).toCompletableFuture();
                    })
                    .emitOn(executor)
                    .map(rs -> true);
        });
    }

    @Override
    public Uni<List<ApiKey>> findAll() {
        Executor executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(
                        () -> session.executeAsync(selectAllStmt.bind()).toCompletableFuture())
                .emitOn(executor)
                .map(rs -> {
                    List<ApiKey> keys = new ArrayList<>();
                    rs.currentPage().forEach(row -> keys.add(fromRow(row)));
                    return keys;
                });
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
