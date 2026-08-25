package aussie.adapter.out.storage.cassandra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.BoundStatement;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.datastax.oss.driver.api.core.cql.Row;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.Role;
import aussie.core.service.auth.RoleEncryptionService;

class CassandraRoleRepositoryTest {

    @Test
    void usesTheTableVersionAsAuthoritative() {
        final var session = mock(CqlSession.class);
        final var encryption = mock(RoleEncryptionService.class);
        final var otherStatement = mock(PreparedStatement.class);
        final var selectStatement = mock(PreparedStatement.class);
        final var selectBound = mock(BoundStatement.class);
        final var result = mock(AsyncResultSet.class);
        final var row = mock(Row.class);
        final var encryptedRole = Role.builder("role-id").build();

        when(session.prepare(anyString())).thenReturn(otherStatement);
        when(session.prepare("SELECT * FROM roles WHERE role_id = ?")).thenReturn(selectStatement);
        when(selectStatement.bind("role-id")).thenReturn(selectBound);
        when(session.executeAsync(selectBound)).thenReturn(CompletableFuture.completedFuture(result));
        when(result.one()).thenReturn(row);
        when(row.getString("encrypted_data")).thenReturn("encrypted");
        when(row.getLong("version")).thenReturn(3L);
        when(encryption.decrypt("encrypted")).thenReturn(encryptedRole);

        final var repository = new CassandraRoleRepository(session, encryption);

        final var found = repository.findById("role-id").await().atMost(Duration.ofSeconds(1));

        assertEquals(3L, found.orElseThrow().version());
    }
}
