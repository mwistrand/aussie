package aussie.adapter.out.storage.cassandra;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.util.Optional;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import org.junit.jupiter.api.Test;

import aussie.spi.StorageAdapterConfig;

class CassandraSessionRegistryTest {

    @Test
    void sharesAndReferenceCountsEquivalentSessions() {
        final var config = mock(StorageAdapterConfig.class);
        final var builder = mock(CqlSessionBuilder.class);
        final var session = mock(CqlSession.class);
        when(config.get(anyString())).thenReturn(Optional.empty());
        when(config.get("aussie.storage.cassandra.keyspace")).thenReturn(Optional.of("session-registry-test"));
        when(builder.withLocalDatacenter(anyString())).thenReturn(builder);
        when(builder.withKeyspace(anyString())).thenReturn(builder);
        when(builder.withConfigLoader(any(DriverConfigLoader.class))).thenReturn(builder);
        when(builder.addContactPoint(any(InetSocketAddress.class))).thenReturn(builder);
        when(builder.build()).thenReturn(session);

        try (final var cqlSession = mockStatic(CqlSession.class)) {
            cqlSession.when(CqlSession::builder).thenReturn(builder);

            final var first = CassandraSessionRegistry.acquire(config, false);
            final var second = CassandraSessionRegistry.acquire(config, true);
            assertSame(first, second);

            CassandraSessionRegistry.release(first);
            verify(session, never()).close();
            CassandraSessionRegistry.release(second);
            verify(session).close();
            cqlSession.verify(CqlSession::builder);
        }
    }
}
