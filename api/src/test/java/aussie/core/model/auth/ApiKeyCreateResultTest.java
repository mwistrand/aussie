package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ApiKeyCreateResult")
class ApiKeyCreateResultTest {

    private final ApiKey metadata =
            ApiKey.builder("k1", "hash").name("test-key").createdBy("admin").build();

    @Test
    @DisplayName("Should create valid result")
    void shouldCreateValidResult() {
        var result = new ApiKeyCreateResult("k1", "ak_live_abc123", metadata);
        assertEquals("k1", result.keyId());
        assertEquals("ak_live_abc123", result.plaintextKey());
        assertEquals(metadata, result.metadata());
    }

    @Test
    @DisplayName("Should throw on null keyId")
    void shouldThrowOnNullKeyId() {
        assertThrows(IllegalArgumentException.class, () -> new ApiKeyCreateResult(null, "key", metadata));
    }

    @Test
    @DisplayName("Should throw on blank plaintextKey")
    void shouldThrowOnBlankPlaintextKey() {
        assertThrows(IllegalArgumentException.class, () -> new ApiKeyCreateResult("k1", "  ", metadata));
    }

    @Test
    @DisplayName("Should throw on null metadata")
    void shouldThrowOnNullMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new ApiKeyCreateResult("k1", "key", null));
    }
}
