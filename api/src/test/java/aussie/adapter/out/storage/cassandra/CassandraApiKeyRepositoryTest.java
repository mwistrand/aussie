package aussie.adapter.out.storage.cassandra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.threading.VertxContextHelper;
import aussie.core.model.auth.ApiKey;
import aussie.core.service.auth.ApiKeyEncryptionService;

class CassandraApiKeyRepositoryTest {

    @Test
    void usesTheTableVersionAsAuthoritative() {
        final var session = mock(CqlSession.class);
        final var encryption = mock(ApiKeyEncryptionService.class);
        final var otherStatement = mock(PreparedStatement.class);
        final var selectStatement = mock(PreparedStatement.class);
        final var selectBound = mock(BoundStatement.class);
        final var result = mock(AsyncResultSet.class);
        final var row = mock(Row.class);
        final var encryptedKey = ApiKey.builder("key-id", "hash").build();

        when(session.prepare(anyString())).thenReturn(otherStatement);
        when(session.prepare("SELECT * FROM api_keys WHERE key_id = ?")).thenReturn(selectStatement);
        when(selectStatement.bind("key-id")).thenReturn(selectBound);
        when(session.executeAsync(selectBound)).thenReturn(CompletableFuture.completedFuture(result));
        when(result.one()).thenReturn(row);
        when(row.getString("encrypted_data")).thenReturn("encrypted");
        when(row.getLong("version")).thenReturn(3L);
        when(encryption.decrypt("encrypted")).thenReturn(encryptedKey);

        final var repository = new CassandraApiKeyRepository(session, encryption);

        final var found = repository.findById("key-id").await().atMost(Duration.ofSeconds(1));

        assertEquals(3L, found.orElseThrow().version());
    }

    @Test
    void fallsBackToLegacyIndexDuringRollingUpgrade() {
        final var session = mock(CqlSession.class);
        final var encryption = mock(ApiKeyEncryptionService.class);
        final var otherStatement = mock(PreparedStatement.class);
        final var lookupStatement = mock(PreparedStatement.class);
        final var legacyStatement = mock(PreparedStatement.class);
        final var lookupBound = mock(BoundStatement.class);
        final var legacyBound = mock(BoundStatement.class);
        final var lookupResult = mock(AsyncResultSet.class);
        final var legacyResult = mock(AsyncResultSet.class);
        final var legacyRow = mock(Row.class);
        final var apiKey = mock(ApiKey.class);

        when(session.prepare(anyString())).thenReturn(otherStatement);
        when(session.prepare("SELECT * FROM api_keys_by_hash WHERE key_hash = ?"))
                .thenReturn(lookupStatement);
        when(session.prepare("SELECT * FROM api_keys WHERE key_hash = ?")).thenReturn(legacyStatement);
        when(lookupStatement.bind("hash")).thenReturn(lookupBound);
        when(legacyStatement.bind("hash")).thenReturn(legacyBound);
        when(session.executeAsync(lookupBound)).thenReturn(CompletableFuture.completedFuture(lookupResult));
        when(session.executeAsync(legacyBound)).thenReturn(CompletableFuture.completedFuture(legacyResult));
        when(legacyResult.one()).thenReturn(legacyRow);
        when(legacyRow.getString("encrypted_data")).thenReturn("encrypted");
        when(encryption.decrypt("encrypted")).thenReturn(apiKey);
        when(apiKey.keyHash()).thenReturn("hash");

        final var repository = new CassandraApiKeyRepository(session, encryption);

        final var found = repository.findByHash("hash").await().atMost(Duration.ofSeconds(1));

        assertSame(apiKey, found.orElseThrow());
        verify(session).executeAsync(legacyBound);
    }

    @Test
    void rejectsLookupRowsDeletedByLegacyNodes() {
        final var session = mock(CqlSession.class);
        final var encryption = mock(ApiKeyEncryptionService.class);
        final var otherStatement = mock(PreparedStatement.class);
        final var lookupStatement = mock(PreparedStatement.class);
        final var selectByIdStatement = mock(PreparedStatement.class);
        final var legacyStatement = mock(PreparedStatement.class);
        final var lookupBound = mock(BoundStatement.class);
        final var selectByIdBound = mock(BoundStatement.class);
        final var legacyBound = mock(BoundStatement.class);
        final var lookupResult = mock(AsyncResultSet.class);
        final var selectByIdResult = mock(AsyncResultSet.class);
        final var legacyResult = mock(AsyncResultSet.class);
        final var lookupRow = mock(Row.class);
        final var staleKey = mock(ApiKey.class);

        when(session.prepare(anyString())).thenReturn(otherStatement);
        when(session.prepare("SELECT * FROM api_keys_by_hash WHERE key_hash = ?"))
                .thenReturn(lookupStatement);
        when(session.prepare("SELECT * FROM api_keys WHERE key_id = ?")).thenReturn(selectByIdStatement);
        when(session.prepare("SELECT * FROM api_keys WHERE key_hash = ?")).thenReturn(legacyStatement);
        when(lookupStatement.bind("hash")).thenReturn(lookupBound);
        when(selectByIdStatement.bind("key-id")).thenReturn(selectByIdBound);
        when(legacyStatement.bind("hash")).thenReturn(legacyBound);
        when(session.executeAsync(lookupBound)).thenReturn(CompletableFuture.completedFuture(lookupResult));
        when(session.executeAsync(selectByIdBound)).thenReturn(CompletableFuture.completedFuture(selectByIdResult));
        when(session.executeAsync(legacyBound)).thenReturn(CompletableFuture.completedFuture(legacyResult));
        when(lookupResult.one()).thenReturn(lookupRow);
        when(lookupRow.getString("encrypted_data")).thenReturn("stale");
        when(encryption.decrypt("stale")).thenReturn(staleKey);
        when(staleKey.id()).thenReturn("key-id");
        when(staleKey.keyHash()).thenReturn("hash");

        final var repository = new CassandraApiKeyRepository(session, encryption);

        final var found = repository.findByHash("hash").await().atMost(Duration.ofSeconds(1));

        assertTrue(found.isEmpty());
        verify(session).executeAsync(selectByIdBound);
        verify(session).executeAsync(legacyBound);
    }

    @Test
    void decryptsPagedRowsOffTheVertxEventLoop() throws Exception {
        final var session = mock(CqlSession.class);
        final var encryption = mock(ApiKeyEncryptionService.class);
        final var statement = mock(PreparedStatement.class);
        final var bound = mock(BoundStatement.class);
        final var result = mock(AsyncResultSet.class);
        final var row = mock(Row.class);
        final var apiKey = ApiKey.builder("key-id", "hash").build();
        final var decryptOnEventLoop = new CompletableFuture<Boolean>();

        when(session.prepare(anyString())).thenReturn(statement);
        when(statement.bind()).thenReturn(bound);
        when(session.executeAsync(bound)).thenReturn(CompletableFuture.completedFuture(result));
        when(result.currentPage()).thenReturn(List.of(row));
        when(result.hasMorePages()).thenReturn(false);
        when(row.getString("encrypted_data")).thenReturn("encrypted");
        when(row.isNull("version")).thenReturn(true);
        when(encryption.decrypt("encrypted")).thenAnswer(invocation -> {
            decryptOnEventLoop.complete(VertxContextHelper.isOnEventLoop());
            return apiKey;
        });

        final var repository = new CassandraApiKeyRepository(session, encryption);
        final var vertx = Vertx.vertx();
        try {
            vertx.runOnContext(() -> repository
                    .findAll()
                    .subscribe()
                    .with(ignoredKeys -> {}, decryptOnEventLoop::completeExceptionally));

            assertFalse(decryptOnEventLoop.get(5, TimeUnit.SECONDS));
        } finally {
            vertx.close().await().atMost(Duration.ofSeconds(5));
        }
    }
}
