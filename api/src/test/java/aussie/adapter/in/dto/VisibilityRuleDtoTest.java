package aussie.adapter.in.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.VisibilityRule;
import aussie.core.model.routing.EndpointVisibility;

@DisplayName("VisibilityRuleDto Tests")
class VisibilityRuleDtoTest {

    @Nested
    @DisplayName("toModel()")
    class ToModelTests {

        @Test
        @DisplayName("Should convert all fields to model")
        void shouldConvertAllFieldsToModel() {
            var dto = new VisibilityRuleDto("/api/**", List.of("GET", "POST"), "PRIVATE");

            var model = dto.toModel();

            assertEquals("/api/**", model.pattern());
            assertEquals(Set.of("GET", "POST"), model.methods());
            assertEquals(EndpointVisibility.PRIVATE, model.visibility());
        }

        @Test
        @DisplayName("Should default null methods to empty set")
        void shouldDefaultNullMethodsToEmptySet() {
            var dto = new VisibilityRuleDto("/api/**", null, "PUBLIC");

            var model = dto.toModel();

            assertTrue(model.methods().isEmpty());
        }

        @Test
        @DisplayName("Should default null visibility to PUBLIC")
        void shouldDefaultNullVisibilityToPublic() {
            var dto = new VisibilityRuleDto("/api/**", null, null);

            var model = dto.toModel();

            assertEquals(EndpointVisibility.PUBLIC, model.visibility());
        }
    }

    @Nested
    @DisplayName("fromModel()")
    class FromModelTests {

        @Test
        @DisplayName("Should convert model to DTO with methods")
        void shouldConvertModelToDtoWithMethods() {
            var model = new VisibilityRule("/api/**", Set.of("GET"), EndpointVisibility.PRIVATE);

            var dto = VisibilityRuleDto.fromModel(model);

            assertEquals("/api/**", dto.pattern());
            assertEquals(List.of("GET"), dto.methods());
            assertEquals("PRIVATE", dto.visibility());
        }

        @Test
        @DisplayName("Should return null methods when model has empty set")
        void shouldReturnNullMethodsWhenEmpty() {
            var model = new VisibilityRule("/api/**", Set.of(), EndpointVisibility.PUBLIC);

            var dto = VisibilityRuleDto.fromModel(model);

            assertNull(dto.methods());
            assertEquals("PUBLIC", dto.visibility());
        }
    }
}
