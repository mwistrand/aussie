package aussie.adapter.in.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TranslationTestRequestDto Tests")
class TranslationTestRequestDtoTest {

    @Test
    @DisplayName("withActivConfig should create request with null config")
    void withActivConfigShouldCreateRequestWithNullConfig() {
        var claims = Map.<String, Object>of("role", "admin");
        var dto = TranslationTestRequestDto.withActivConfig("test-issuer", "test-subject", claims);

        assertNull(dto.config());
        assertEquals("test-issuer", dto.issuer());
        assertEquals("test-subject", dto.subject());
        assertEquals(claims, dto.claims());
    }
}
