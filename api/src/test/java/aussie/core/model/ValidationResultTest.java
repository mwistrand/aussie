package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ValidationResult")
class ValidationResultTest {

    @Nested
    @DisplayName("Valid Result")
    class ValidResultTests {

        @Test
        @DisplayName("Should create valid result")
        void shouldCreateValidResult() {
            var result = ValidationResult.valid();

            assertTrue(result.isValid());
            assertFalse(result.isInvalid());
            assertInstanceOf(ValidationResult.Valid.class, result);
        }
    }

    @Nested
    @DisplayName("Invalid Result")
    class InvalidResultTests {

        @Test
        @DisplayName("Should create invalid result with custom status code")
        void shouldCreateInvalidWithCustomStatusCode() {
            var result = ValidationResult.invalid("Custom error", 400);

            assertFalse(result.isValid());
            assertTrue(result.isInvalid());
            assertInstanceOf(ValidationResult.Invalid.class, result);

            var invalid = (ValidationResult.Invalid) result;
            assertEquals("Custom error", invalid.reason());
            assertEquals(400, invalid.suggestedStatusCode());
        }

        @Test
        @DisplayName("Should create payload too large result")
        void shouldCreatePayloadTooLargeResult() {
            var result = ValidationResult.payloadTooLarge("Body too large");

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals("Body too large", invalid.reason());
            assertEquals(413, invalid.suggestedStatusCode());
        }

        @Test
        @DisplayName("Should create header too large result")
        void shouldCreateHeaderTooLargeResult() {
            var result = ValidationResult.headerTooLarge("Header too large");

            assertInstanceOf(ValidationResult.Invalid.class, result);
            var invalid = (ValidationResult.Invalid) result;
            assertEquals("Header too large", invalid.reason());
            assertEquals(431, invalid.suggestedStatusCode());
        }
    }
}
