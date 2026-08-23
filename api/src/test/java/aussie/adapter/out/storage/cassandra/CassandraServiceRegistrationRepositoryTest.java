package aussie.adapter.out.storage.cassandra;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import aussie.core.model.service.ServiceRegistration;

class CassandraServiceRegistrationRepositoryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void shouldInitializeLegacyVersionBeforeRetryingConditionalUpdate() {
        final var session = mock(CqlSession.class);
        final var otherStatement = mock(PreparedStatement.class);
        final var updateStatement = mock(PreparedStatement.class);
        final var initializeStatement = mock(PreparedStatement.class);
        final var selectGenerationStatement = mock(PreparedStatement.class);
        final var updateGenerationStatement = mock(PreparedStatement.class);
        final var updateBound = mock(BoundStatement.class);
        final var initializeBound = mock(BoundStatement.class);
        final var selectGenerationBound = mock(BoundStatement.class);
        final var updateGenerationBound = mock(BoundStatement.class);

        when(session.prepare(anyString())).thenAnswer(invocation -> {
            final var cql = invocation.getArgument(0, String.class);
            if (cql.contains("SET version = 1")) {
                return initializeStatement;
            }
            if (cql.startsWith("SELECT generation")) {
                return selectGenerationStatement;
            }
            if (cql.startsWith("UPDATE service_config_generation")) {
                return updateGenerationStatement;
            }
            if (cql.contains("IF version = ?") && cql.contains("UPDATE service_registrations")) {
                return updateStatement;
            }
            return otherStatement;
        });
        when(updateStatement.bind(any(Object[].class))).thenReturn(updateBound);
        when(initializeStatement.bind(any(Object[].class))).thenReturn(initializeBound);
        when(selectGenerationStatement.bind(any(Object[].class))).thenReturn(selectGenerationBound);
        when(updateGenerationStatement.bind(any(Object[].class))).thenReturn(updateGenerationBound);

        final var missingVersion = mock(AsyncResultSet.class);
        final var legacyRow = mock(Row.class);
        when(missingVersion.wasApplied()).thenReturn(false);
        when(missingVersion.one()).thenReturn(legacyRow);
        when(legacyRow.isNull("version")).thenReturn(true);

        final var initialized = mock(AsyncResultSet.class);
        when(initialized.wasApplied()).thenReturn(true);
        final var updated = mock(AsyncResultSet.class);
        when(updated.wasApplied()).thenReturn(true);
        final var generationResult = mock(AsyncResultSet.class);
        final var generationRow = mock(Row.class);
        when(generationResult.one()).thenReturn(generationRow);
        when(generationRow.getLong("generation")).thenReturn(1L);
        final var generationUpdated = mock(AsyncResultSet.class);
        when(generationUpdated.wasApplied()).thenReturn(true);

        when(session.executeAsync(updateBound))
                .thenReturn(CompletableFuture.completedFuture(missingVersion))
                .thenReturn(CompletableFuture.completedFuture(updated));
        when(session.executeAsync(initializeBound)).thenReturn(CompletableFuture.completedFuture(initialized));
        when(session.executeAsync(selectGenerationBound))
                .thenReturn(CompletableFuture.completedFuture(generationResult));
        when(session.executeAsync(updateGenerationBound))
                .thenReturn(CompletableFuture.completedFuture(generationUpdated));

        final var repository = new CassandraServiceRegistrationRepository(new ObjectMapper(), session);
        final var registration = ServiceRegistration.builder("legacy-service")
                .baseUrl("http://192.0.2.10:8080")
                .version(2)
                .build();

        final var result = repository.replaceIfVersion(registration, 1).await().atMost(TIMEOUT);

        assertTrue(result.applied());
        verify(session).prepare(argThat((String cql) -> cql.contains("IF version = NULL AND display_name != NULL")));
        verify(session, times(2)).executeAsync(updateBound);
        verify(session).executeAsync(initializeBound);
    }
}
