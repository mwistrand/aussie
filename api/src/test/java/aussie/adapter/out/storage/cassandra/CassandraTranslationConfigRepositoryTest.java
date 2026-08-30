package aussie.adapter.out.storage.cassandra;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import org.junit.jupiter.api.Test;

class CassandraTranslationConfigRepositoryTest {

    @Test
    void rejectsLookupRowsDeletedByLegacyNodes() {
        final var session = mock(CqlSession.class);
        final var otherStatement = mock(PreparedStatement.class);
        final var metadataStatement = mock(PreparedStatement.class);
        final var lookupStatement = mock(PreparedStatement.class);
        final var selectByIdStatement = mock(PreparedStatement.class);
        final var legacyStatement = mock(PreparedStatement.class);
        final var metadataBound = mock(BoundStatement.class);
        final var lookupBound = mock(BoundStatement.class);
        final var selectByIdBound = mock(BoundStatement.class);
        final var legacyBound = mock(BoundStatement.class);
        final var metadataResult = mock(AsyncResultSet.class);
        final var lookupResult = mock(AsyncResultSet.class);
        final var selectByIdResult = mock(AsyncResultSet.class);
        final var legacyResult = mock(AsyncResultSet.class);
        final var lookupRow = mock(Row.class);

        when(session.prepare(anyString())).thenReturn(otherStatement);
        when(session.prepare("SELECT value FROM translation_config_metadata WHERE key = ?"))
                .thenReturn(metadataStatement);
        when(session.prepare("SELECT * FROM translation_config_versions_by_number WHERE version = ?"))
                .thenReturn(lookupStatement);
        when(session.prepare("SELECT * FROM translation_config_versions WHERE id = ?"))
                .thenReturn(selectByIdStatement);
        when(session.prepare("SELECT * FROM translation_config_versions WHERE version = ?"))
                .thenReturn(legacyStatement);
        when(metadataStatement.bind("active_version_id")).thenReturn(metadataBound);
        when(lookupStatement.bind(7)).thenReturn(lookupBound);
        when(selectByIdStatement.bind("version-id")).thenReturn(selectByIdBound);
        when(legacyStatement.bind(7)).thenReturn(legacyBound);
        when(session.executeAsync(metadataBound)).thenReturn(CompletableFuture.completedFuture(metadataResult));
        when(session.executeAsync(lookupBound)).thenReturn(CompletableFuture.completedFuture(lookupResult));
        when(session.executeAsync(selectByIdBound)).thenReturn(CompletableFuture.completedFuture(selectByIdResult));
        when(session.executeAsync(legacyBound)).thenReturn(CompletableFuture.completedFuture(legacyResult));
        when(lookupResult.one()).thenReturn(lookupRow);
        when(lookupRow.getString("id")).thenReturn("version-id");
        when(lookupRow.getInt("version")).thenReturn(7);
        when(lookupRow.getString("config_json")).thenReturn("""
                        {"version":1,"sources":[],"transforms":[],
                         "mappings":{"roleToPermissions":{},"directPermissions":{}},
                         "defaults":{"denyIfNoMatch":true,"includeUnmapped":false}}
                        """);
        when(lookupRow.getString("created_by")).thenReturn("reviewer");
        when(lookupRow.getInstant("created_at")).thenReturn(Instant.parse("2026-08-23T12:00:00Z"));
        when(lookupRow.getString("comment")).thenReturn("reviewed");

        final var repository = new CassandraTranslationConfigRepository(session);

        final var found = repository.findByVersion(7).await().atMost(Duration.ofSeconds(1));

        assertTrue(found.isEmpty());
        verify(session).executeAsync(selectByIdBound);
        verify(session).executeAsync(legacyBound);
    }
}
