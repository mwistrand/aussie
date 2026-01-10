package aussie.adapter.out.storage.cassandra;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Vertx;

import aussie.core.model.auth.TranslationConfigSchema;
import aussie.core.model.auth.TranslationConfigVersion;
import aussie.core.port.out.TranslationConfigRepository;

/**
 * Cassandra implementation of TranslationConfigRepository.
 *
 * <p>Provides durable, distributed storage for translation configurations with
 * version history for auditing and rollback capability.
 *
 * <h2>Schema</h2>
 * <pre>
 * CREATE TABLE IF NOT EXISTS translation_config_versions (
 *     id text PRIMARY KEY,
 *     version int,
 *     config_json text,
 *     created_by text,
 *     created_at timestamp,
 *     comment text
 * );
 *
 * CREATE TABLE IF NOT EXISTS translation_config_metadata (
 *     key text PRIMARY KEY,
 *     value text,
 *     updated_at timestamp
 * );
 * </pre>
 *
 * <p>The active version is determined by the {@code active_version_id} entry in the
 * metadata table, not by a row-level flag. This ensures consistency and avoids stale data.
 */
public class CassandraTranslationConfigRepository implements TranslationConfigRepository {

    private static final String ACTIVE_VERSION_KEY = "active_version_id";
    private static final String VERSION_COUNTER_KEY = "version_counter";
    private static final int MAX_CAS_RETRIES = 5;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final CqlSession session;
    private final PreparedStatement insertVersionStmt;
    private final PreparedStatement selectByIdStmt;
    private final PreparedStatement selectByVersionStmt;
    private final PreparedStatement selectAllStmt;
    private final PreparedStatement deleteStmt;
    private final PreparedStatement getMetadataStmt;
    private final PreparedStatement setMetadataStmt;
    private final PreparedStatement casMetadataStmt;
    private final PreparedStatement insertMetadataStmt;

    public CassandraTranslationConfigRepository(CqlSession session) {
        this.session = session;
        this.insertVersionStmt = prepareInsertVersion();
        this.selectByIdStmt = prepareSelectById();
        this.selectByVersionStmt = prepareSelectByVersion();
        this.selectAllStmt = prepareSelectAll();
        this.deleteStmt = prepareDelete();
        this.getMetadataStmt = prepareGetMetadata();
        this.setMetadataStmt = prepareSetMetadata();
        this.casMetadataStmt = prepareCasMetadata();
        this.insertMetadataStmt = prepareInsertMetadata();
    }

    private PreparedStatement prepareInsertVersion() {
        return session.prepare(
                """
                INSERT INTO translation_config_versions (id, version, config_json, created_by, created_at, comment)
                VALUES (?, ?, ?, ?, ?, ?)
                """);
    }

    private PreparedStatement prepareSelectById() {
        return session.prepare("SELECT * FROM translation_config_versions WHERE id = ?");
    }

    private PreparedStatement prepareSelectByVersion() {
        return session.prepare("SELECT * FROM translation_config_versions WHERE version = ?");
    }

    private PreparedStatement prepareSelectAll() {
        return session.prepare("SELECT * FROM translation_config_versions");
    }

    private PreparedStatement prepareDelete() {
        return session.prepare("DELETE FROM translation_config_versions WHERE id = ?");
    }

    private PreparedStatement prepareGetMetadata() {
        return session.prepare("SELECT value FROM translation_config_metadata WHERE key = ?");
    }

    private PreparedStatement prepareSetMetadata() {
        return session.prepare(
                """
                INSERT INTO translation_config_metadata (key, value, updated_at)
                VALUES (?, ?, toTimestamp(now()))
                """);
    }

    private PreparedStatement prepareCasMetadata() {
        return session.prepare(
                """
                UPDATE translation_config_metadata
                SET value = ?, updated_at = toTimestamp(now())
                WHERE key = ?
                IF value = ?
                """);
    }

    private PreparedStatement prepareInsertMetadata() {
        return session.prepare(
                """
                INSERT INTO translation_config_metadata (key, value, updated_at)
                VALUES (?, ?, toTimestamp(now()))
                IF NOT EXISTS
                """);
    }

    @Override
    public Uni<Void> save(TranslationConfigVersion version) {
        final var executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    final var configJson = serializeConfig(version.config());
                    final var bound = insertVersionStmt.bind(
                            version.id(),
                            version.version(),
                            configJson,
                            version.createdBy(),
                            version.createdAt(),
                            version.comment());
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .replaceWithVoid();
    }

    @Override
    public Uni<Optional<TranslationConfigVersion>> getActive() {
        return getMetadataValue(ACTIVE_VERSION_KEY).flatMap(activeId -> {
            if (activeId.isEmpty()) {
                return Uni.createFrom().item(Optional.empty());
            }
            return findById(activeId.get()).map(opt -> opt.map(TranslationConfigVersion::activate));
        });
    }

    @Override
    public Uni<Optional<TranslationConfigVersion>> findById(String id) {
        final var executor = getContextExecutor();
        return getMetadataValue(ACTIVE_VERSION_KEY).flatMap(activeId -> Uni.createFrom()
                .completionStage(() -> {
                    final var bound = selectByIdStmt.bind(id);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .map(rs -> {
                    final var row = rs.one();
                    if (row == null) {
                        return Optional.<TranslationConfigVersion>empty();
                    }
                    final var version = fromRow(row);
                    final var isActive = activeId.isPresent() && activeId.get().equals(id);
                    return Optional.of(isActive ? version.activate() : version.deactivate());
                }));
    }

    @Override
    public Uni<Optional<TranslationConfigVersion>> findByVersion(int versionNumber) {
        final var executor = getContextExecutor();
        return getMetadataValue(ACTIVE_VERSION_KEY).flatMap(activeId -> Uni.createFrom()
                .completionStage(() -> {
                    final var bound = selectByVersionStmt.bind(versionNumber);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .map(rs -> {
                    final var row = rs.one();
                    if (row == null) {
                        return Optional.<TranslationConfigVersion>empty();
                    }
                    final var version = fromRow(row);
                    final var isActive = activeId.isPresent() && activeId.get().equals(version.id());
                    return Optional.of(isActive ? version.activate() : version.deactivate());
                }));
    }

    @Override
    public Uni<List<TranslationConfigVersion>> listVersions() {
        final var executor = getContextExecutor();
        return getMetadataValue(ACTIVE_VERSION_KEY).flatMap(activeId -> Uni.createFrom()
                .completionStage(
                        () -> session.executeAsync(selectAllStmt.bind()).toCompletableFuture())
                .emitOn(executor)
                .map(rs -> {
                    final List<TranslationConfigVersion> versions = new ArrayList<>();
                    for (var row : rs.currentPage()) {
                        final var version = fromRow(row);
                        final var isActive =
                                activeId.isPresent() && activeId.get().equals(version.id());
                        versions.add(isActive ? version.activate() : version.deactivate());
                    }
                    versions.sort(Comparator.comparing(TranslationConfigVersion::version)
                            .reversed());
                    return versions;
                }));
    }

    @Override
    public Uni<List<TranslationConfigVersion>> listVersions(int limit, int offset) {
        return listVersions().map(all -> all.stream().skip(offset).limit(limit).toList());
    }

    @Override
    public Uni<Integer> getNextVersionNumber() {
        return attemptVersionIncrement(0);
    }

    /**
     * Attempts to atomically increment the version counter using LWT (Lightweight Transactions).
     * Uses compare-and-swap to prevent race conditions when multiple instances try to
     * increment the counter simultaneously.
     */
    private Uni<Integer> attemptVersionIncrement(int attempt) {
        if (attempt >= MAX_CAS_RETRIES) {
            return Uni.createFrom()
                    .failure(new RuntimeException(
                            "Failed to claim next version number after " + MAX_CAS_RETRIES + " attempts"));
        }

        final var executor = getContextExecutor();
        return getMetadataValue(VERSION_COUNTER_KEY).flatMap(current -> {
            final var nextVersion = current.map(v -> Integer.parseInt(v) + 1).orElse(1);

            // Use different statement depending on whether counter exists
            final Supplier<BoundStatement> boundSupplier;
            if (current.isEmpty()) {
                // First version: use INSERT IF NOT EXISTS
                boundSupplier = () -> insertMetadataStmt.bind(VERSION_COUNTER_KEY, String.valueOf(nextVersion));
            } else {
                // Subsequent versions: use UPDATE IF value = current
                boundSupplier =
                        () -> casMetadataStmt.bind(String.valueOf(nextVersion), VERSION_COUNTER_KEY, current.get());
            }

            return Uni.createFrom()
                    .completionStage(
                            () -> session.executeAsync(boundSupplier.get()).toCompletableFuture())
                    .emitOn(executor)
                    .flatMap(rs -> {
                        final var wasApplied = rs.wasApplied();
                        if (wasApplied) {
                            return Uni.createFrom().item(nextVersion);
                        }
                        // CAS failed: another instance won the race, retry
                        return attemptVersionIncrement(attempt + 1);
                    });
        });
    }

    @Override
    public Uni<Boolean> setActive(String versionId) {
        return findById(versionId).flatMap(opt -> {
            if (opt.isEmpty()) {
                return Uni.createFrom().item(false);
            }
            final var executor = getContextExecutor();
            return Uni.createFrom()
                    .completionStage(() -> {
                        final var bound = setMetadataStmt.bind(ACTIVE_VERSION_KEY, versionId);
                        return session.executeAsync(bound).toCompletableFuture();
                    })
                    .emitOn(executor)
                    .map(rs -> true);
        });
    }

    @Override
    public Uni<Boolean> delete(String versionId) {
        return getMetadataValue(ACTIVE_VERSION_KEY).flatMap(activeId -> {
            if (activeId.isPresent() && activeId.get().equals(versionId)) {
                return Uni.createFrom().item(false);
            }
            return findById(versionId).flatMap(opt -> {
                if (opt.isEmpty()) {
                    return Uni.createFrom().item(false);
                }
                final var executor = getContextExecutor();
                return Uni.createFrom()
                        .completionStage(() -> {
                            final var bound = deleteStmt.bind(versionId);
                            return session.executeAsync(bound).toCompletableFuture();
                        })
                        .emitOn(executor)
                        .map(rs -> true);
            });
        });
    }

    private Uni<Optional<String>> getMetadataValue(String key) {
        final var executor = getContextExecutor();
        return Uni.createFrom()
                .completionStage(() -> {
                    final var bound = getMetadataStmt.bind(key);
                    return session.executeAsync(bound).toCompletableFuture();
                })
                .emitOn(executor)
                .map(rs -> {
                    final var row = rs.one();
                    return row != null ? Optional.ofNullable(row.getString("value")) : Optional.empty();
                });
    }

    private TranslationConfigVersion fromRow(Row row) {
        final var configJson = row.getString("config_json");
        final var config = deserializeConfig(configJson);
        // Active state is determined by metadata lookup, not the row itself
        return new TranslationConfigVersion(
                row.getString("id"),
                row.getInt("version"),
                config,
                false,
                row.getString("created_by"),
                row.getInstant("created_at"),
                row.getString("comment"));
    }

    private String serializeConfig(TranslationConfigSchema config) {
        try {
            return OBJECT_MAPPER.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize TranslationConfigSchema", e);
        }
    }

    private TranslationConfigSchema deserializeConfig(String json) {
        try {
            return OBJECT_MAPPER.readValue(json, TranslationConfigSchema.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize TranslationConfigSchema", e);
        }
    }

    /**
     * Get an executor that will run on the Vert.x context if available,
     * otherwise falls back to the default worker pool.
     */
    private Executor getContextExecutor() {
        final var context = Vertx.currentContext();
        if (context != null) {
            return command -> context.runOnContext(v -> command.run());
        }
        return Infrastructure.getDefaultWorkerPool();
    }
}
