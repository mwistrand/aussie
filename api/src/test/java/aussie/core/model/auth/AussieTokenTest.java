package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AussieToken")
class AussieTokenTest {

    private static final Instant FUTURE = Instant.now().plusSeconds(3600);

    @Test
    @DisplayName("Should default null jws to empty string")
    void shouldDefaultNullJws() {
        var token = new AussieToken(null, "user1", FUTURE, Map.of());
        assertEquals("", token.jws());
        assertFalse(token.hasToken());
    }

    @Test
    @DisplayName("hasToken should return true for non-blank jws")
    void shouldReturnTrueForNonBlankJws() {
        var token = new AussieToken("eyJhbGciOi...", "user1", FUTURE, Map.of());
        assertTrue(token.hasToken());
    }

    @Test
    @DisplayName("Should throw on null subject")
    void shouldThrowOnNullSubject() {
        assertThrows(IllegalArgumentException.class, () -> new AussieToken("jws", null, FUTURE, Map.of()));
    }

    @Test
    @DisplayName("Should throw on null expiresAt")
    void shouldThrowOnNullExpiresAt() {
        assertThrows(IllegalArgumentException.class, () -> new AussieToken("jws", "user1", null, Map.of()));
    }

    @Test
    @DisplayName("Should default null claims to empty map")
    void shouldDefaultNullClaims() {
        var token = new AussieToken("jws", "user1", FUTURE, null);
        assertEquals(Map.of(), token.claims());
    }
}
