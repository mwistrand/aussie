package aussie.adapter.in.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.TranslationConfigSchema;

@DisplayName("TranslationConfigUploadDto Tests")
class TranslationConfigUploadDtoTest {

    @Test
    @DisplayName("Two-arg constructor should default activate to false")
    void twoArgConstructorShouldDefaultActivateToFalse() {
        var schema = new TranslationConfigSchema(1, null, null, null, null);
        var dto = new TranslationConfigUploadDto(schema, "initial upload");

        assertEquals(schema, dto.config());
        assertEquals("initial upload", dto.comment());
        assertFalse(dto.activate());
    }

    @Test
    @DisplayName("Three-arg constructor should preserve activate flag")
    void threeArgConstructorShouldPreserveActivateFlag() {
        var schema = new TranslationConfigSchema(1, null, null, null, null);
        var dto = new TranslationConfigUploadDto(schema, "activate now", true);

        assertTrue(dto.activate());
    }
}
