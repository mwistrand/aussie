package aussie.adapter.out.storage.cassandra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.CqlSessionBuilder;
import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import aussie.spi.StorageAdapterConfig;

class CassandraSessionRegistryTest {

    @Test
    void buildsDistinctStorageAndAuthSessionsFromTheirOwnProperties() {
        final var values = Map.of(
                "aussie.storage.cassandra.contact-points", "127.0.0.1:9042",
                "aussie.storage.cassandra.datacenter", "storage-dc",
                "aussie.storage.cassandra.keyspace", "storage-keyspace",
                "aussie.auth.storage.cassandra.contact-points", "127.0.0.2:9043",
                "aussie.auth.storage.cassandra.datacenter", "auth-dc",
                "aussie.auth.storage.cassandra.keyspace", "auth-keyspace",
                "aussie.auth.storage.cassandra.username", "auth-user",
                "aussie.auth.storage.cassandra.password", "auth-password",
                "aussie.resiliency.cassandra.query-timeout", "PT11S");
        final var config = mock(StorageAdapterConfig.class);
        final var builder = mock(CqlSessionBuilder.class);
        final var session = mock(CqlSession.class);
        when(config.get(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(values.get(invocation.getArgument(0))));
        when(builder.withLocalDatacenter(anyString())).thenReturn(builder);
        when(builder.withKeyspace(anyString())).thenReturn(builder);
        when(builder.withConfigLoader(any(DriverConfigLoader.class))).thenReturn(builder);
        when(builder.addContactPoint(any(InetSocketAddress.class))).thenReturn(builder);
        when(builder.withAuthCredentials(anyString(), anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(session);

        try (final var cqlSession = mockStatic(CqlSession.class)) {
            cqlSession.when(CqlSession::builder).thenReturn(builder);

            final var storage = CassandraSessionRegistry.acquire(config, false);
            final var auth = CassandraSessionRegistry.acquire(config, true);

            verify(builder).withLocalDatacenter("storage-dc");
            verify(builder).withKeyspace("storage-keyspace");
            verify(builder).addContactPoint(new InetSocketAddress("127.0.0.1", 9042));
            verify(builder).withLocalDatacenter("auth-dc");
            verify(builder).withKeyspace("auth-keyspace");
            verify(builder).addContactPoint(new InetSocketAddress("127.0.0.2", 9043));
            verify(builder).withAuthCredentials("auth-user", "auth-password");

            final var loaders = ArgumentCaptor.forClass(DriverConfigLoader.class);
            verify(builder, times(2)).withConfigLoader(loaders.capture());
            loaders.getAllValues()
                    .forEach(loader -> assertEquals(
                            Duration.ofSeconds(11),
                            loader.getInitialConfig()
                                    .getDefaultProfile()
                                    .getDuration(DefaultDriverOption.REQUEST_TIMEOUT)));

            CassandraSessionRegistry.release(storage);
            CassandraSessionRegistry.release(auth);
        }
    }

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
