package aussie.adapter.out.storage.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import aussie.core.model.session.Session;

class RedisSessionRepositoryTest {

    private final ObjectMapper mapper =
            new ObjectMapper().registerModule(new Jdk8Module()).registerModule(new JavaTimeModule());

    @Test
    void versionedJsonPreservesClaimTypesAndDelimiters() {
        final var session = new Session(
                "session-1",
                "user|1",
                "issuer;1",
                Map.of("text", "a|b;c=d,é", "number", 42, "boolean", true, "nested", List.of("x", 7)),
                Set.of("read;write", "a=b"),
                Instant.ofEpochMilli(1000),
                Instant.ofEpochMilli(2000),
                Instant.ofEpochMilli(1500),
                "agent|;=,",
                "127.0.0.1");

        final var encoded = RedisSessionRepository.serialize(mapper, session);
        final var decoded = RedisSessionRepository.deserialize(mapper, encoded);

        assertTrue(encoded.contains("\"schemaVersion\":1"));
        assertEquals(session, decoded);
    }

    @Test
    void readsLegacyDelimiterRecords() {
        final var legacy = "id|user|issuer|role=admin|read;write|1000|2000|1500|agent|127.0.0.1";

        final var session = RedisSessionRepository.deserialize(mapper, legacy);

        assertEquals("admin", session.claims().get("role"));
        assertEquals(Set.of("read", "write"), session.permissions());
    }

    @Test
    void rejectsOversizedRecords() {
        final var oversized = "x".repeat(64 * 1024 + 1);

        assertThrows(IllegalArgumentException.class, () -> RedisSessionRepository.deserialize(mapper, oversized));
    }
}
