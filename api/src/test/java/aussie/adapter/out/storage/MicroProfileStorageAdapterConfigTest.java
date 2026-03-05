package aussie.adapter.out.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("MicroProfileStorageAdapterConfig")
@ExtendWith(MockitoExtension.class)
class MicroProfileStorageAdapterConfigTest {

    @Mock
    private Config config;

    @InjectMocks
    private MicroProfileStorageAdapterConfig adapterConfig;

    @Nested
    @DisplayName("getRequired()")
    class GetRequiredTests {

        @Test
        @DisplayName("should return value when present")
        void shouldReturnValueWhenPresent() {
            when(config.getOptionalValue("db.host", String.class)).thenReturn(Optional.of("localhost"));

            final var result = adapterConfig.getRequired("db.host");

            assertEquals("localhost", result);
        }

        @Test
        @DisplayName("should throw IllegalStateException when missing")
        void shouldThrowWhenMissing() {
            when(config.getOptionalValue("db.host", String.class)).thenReturn(Optional.empty());

            final var exception = assertThrows(IllegalStateException.class, () -> adapterConfig.getRequired("db.host"));

            assertTrue(exception.getMessage().contains("db.host"));
        }
    }

    @Nested
    @DisplayName("get()")
    class GetTests {

        @Test
        @DisplayName("should return Optional with value when present")
        void shouldReturnValueWhenPresent() {
            when(config.getOptionalValue("db.port", String.class)).thenReturn(Optional.of("9042"));

            final var result = adapterConfig.get("db.port");

            assertTrue(result.isPresent());
            assertEquals("9042", result.get());
        }

        @Test
        @DisplayName("should return empty Optional when missing")
        void shouldReturnEmptyWhenMissing() {
            when(config.getOptionalValue("db.port", String.class)).thenReturn(Optional.empty());

            final var result = adapterConfig.get("db.port");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getOrDefault()")
    class GetOrDefaultTests {

        @Test
        @DisplayName("should return value when present")
        void shouldReturnValueWhenPresent() {
            when(config.getOptionalValue("db.keyspace", String.class)).thenReturn(Optional.of("aussie"));

            final var result = adapterConfig.getOrDefault("db.keyspace", "default_ks");

            assertEquals("aussie", result);
        }

        @Test
        @DisplayName("should return default when missing")
        void shouldReturnDefaultWhenMissing() {
            when(config.getOptionalValue("db.keyspace", String.class)).thenReturn(Optional.empty());

            final var result = adapterConfig.getOrDefault("db.keyspace", "default_ks");

            assertEquals("default_ks", result);
        }
    }

    @Nested
    @DisplayName("getWithPrefix()")
    class GetWithPrefixTests {

        @Test
        @DisplayName("should return matching properties")
        void shouldReturnMatchingProperties() {
            when(config.getPropertyNames()).thenReturn(List.of("cassandra.host", "cassandra.port", "redis.host"));
            when(config.getOptionalValue("cassandra.host", String.class)).thenReturn(Optional.of("localhost"));
            when(config.getOptionalValue("cassandra.port", String.class)).thenReturn(Optional.of("9042"));

            final var result = adapterConfig.getWithPrefix("cassandra.");

            assertEquals(2, result.size());
            assertEquals("localhost", result.get("cassandra.host"));
            assertEquals("9042", result.get("cassandra.port"));
        }

        @Test
        @DisplayName("should return empty map when no matching keys")
        void shouldReturnEmptyMapWhenNoMatch() {
            when(config.getPropertyNames()).thenReturn(List.of("redis.host", "redis.port"));

            final var result = adapterConfig.getWithPrefix("cassandra.");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getInt()")
    class GetIntTests {

        @Test
        @DisplayName("should return value when present")
        void shouldReturnValueWhenPresent() {
            when(config.getOptionalValue("pool.size", Integer.class)).thenReturn(Optional.of(10));

            final var result = adapterConfig.getInt("pool.size");

            assertTrue(result.isPresent());
            assertEquals(10, result.get());
        }

        @Test
        @DisplayName("should return empty when missing")
        void shouldReturnEmptyWhenMissing() {
            when(config.getOptionalValue("pool.size", Integer.class)).thenReturn(Optional.empty());

            final var result = adapterConfig.getInt("pool.size");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getLong()")
    class GetLongTests {

        @Test
        @DisplayName("should return value when present")
        void shouldReturnValueWhenPresent() {
            when(config.getOptionalValue("timeout.ms", Long.class)).thenReturn(Optional.of(5000L));

            final var result = adapterConfig.getLong("timeout.ms");

            assertTrue(result.isPresent());
            assertEquals(5000L, result.get());
        }

        @Test
        @DisplayName("should return empty when missing")
        void shouldReturnEmptyWhenMissing() {
            when(config.getOptionalValue("timeout.ms", Long.class)).thenReturn(Optional.empty());

            final var result = adapterConfig.getLong("timeout.ms");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getBoolean()")
    class GetBooleanTests {

        @Test
        @DisplayName("should return value when present")
        void shouldReturnValueWhenPresent() {
            when(config.getOptionalValue("ssl.enabled", Boolean.class)).thenReturn(Optional.of(true));

            final var result = adapterConfig.getBoolean("ssl.enabled");

            assertTrue(result.isPresent());
            assertEquals(true, result.get());
        }

        @Test
        @DisplayName("should return empty when missing")
        void shouldReturnEmptyWhenMissing() {
            when(config.getOptionalValue("ssl.enabled", Boolean.class)).thenReturn(Optional.empty());

            final var result = adapterConfig.getBoolean("ssl.enabled");

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getDuration()")
    class GetDurationTests {

        @Test
        @DisplayName("should parse valid ISO-8601 duration")
        void shouldParseValidDuration() {
            when(config.getOptionalValue("cache.ttl", String.class)).thenReturn(Optional.of("PT30S"));

            final var result = adapterConfig.getDuration("cache.ttl");

            assertTrue(result.isPresent());
            assertEquals(Duration.ofSeconds(30), result.get());
        }

        @Test
        @DisplayName("should parse complex ISO-8601 duration")
        void shouldParseComplexDuration() {
            when(config.getOptionalValue("cache.ttl", String.class)).thenReturn(Optional.of("PT5M30S"));

            final var result = adapterConfig.getDuration("cache.ttl");

            assertTrue(result.isPresent());
            assertEquals(Duration.ofMinutes(5).plusSeconds(30), result.get());
        }

        @Test
        @DisplayName("should return empty when missing")
        void shouldReturnEmptyWhenMissing() {
            when(config.getOptionalValue("cache.ttl", String.class)).thenReturn(Optional.empty());

            final var result = adapterConfig.getDuration("cache.ttl");

            assertTrue(result.isEmpty());
        }
    }
}
