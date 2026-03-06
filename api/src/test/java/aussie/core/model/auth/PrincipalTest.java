package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Principal")
class PrincipalTest {

    @Test
    @DisplayName("Should throw on null id")
    void shouldThrowOnNullId() {
        assertThrows(IllegalArgumentException.class, () -> new Principal(null, "name", "user", Map.of()));
    }

    @Test
    @DisplayName("Should throw on blank id")
    void shouldThrowOnBlankId() {
        assertThrows(IllegalArgumentException.class, () -> new Principal("  ", "name", "user", Map.of()));
    }

    @Test
    @DisplayName("Should default blank name to id")
    void shouldDefaultBlankName() {
        var principal = new Principal("user1", null, "user", Map.of());
        assertEquals("user1", principal.name());
    }

    @Test
    @DisplayName("Should default blank type to unknown")
    void shouldDefaultBlankType() {
        var principal = new Principal("user1", "User", null, Map.of());
        assertEquals("unknown", principal.type());
    }

    @Test
    @DisplayName("Should default null attributes to empty map")
    void shouldDefaultNullAttributes() {
        var principal = new Principal("user1", "User", "user", null);
        assertEquals(Map.of(), principal.attributes());
    }

    @Test
    @DisplayName("Factory methods should set correct type")
    void factoryMethodsShouldSetCorrectType() {
        assertEquals("system", Principal.system("gateway").type());
        assertEquals("service", Principal.service("svc1", "My Service").type());
        assertEquals("user", Principal.user("u1", "Alice").type());
    }
}
