package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TokenValidationResult.Valid")
class TokenValidationResultTest {

    @Test
    @DisplayName("Should throw on null subject")
    void shouldThrowOnNullSubject() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TokenValidationResult.Valid(null, "issuer", Map.of(), Instant.now()));
    }

    @Test
    @DisplayName("Should throw on blank subject")
    void shouldThrowOnBlankSubject() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TokenValidationResult.Valid("  ", "issuer", Map.of(), Instant.now()));
    }

    @Test
    @DisplayName("Should throw on null issuer")
    void shouldThrowOnNullIssuer() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new TokenValidationResult.Valid("sub", null, Map.of(), Instant.now()));
    }

    @Test
    @DisplayName("Should default null claims to empty map")
    void shouldDefaultNullClaims() {
        var valid = new TokenValidationResult.Valid("sub", "iss", null, Instant.now());
        assertEquals(Map.of(), valid.claims());
    }
}
