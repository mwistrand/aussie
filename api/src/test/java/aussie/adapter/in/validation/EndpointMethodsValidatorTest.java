package aussie.adapter.in.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Set;

import jakarta.validation.ConstraintValidatorContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.in.dto.EndpointConfigDto;

@DisplayName("EndpointMethodsValidator")
class EndpointMethodsValidatorTest {

    private final EndpointMethodsValidator validator = new EndpointMethodsValidator();
    private ConstraintValidatorContext context;
    private ConstraintValidatorContext.ConstraintViolationBuilder violationBuilder;
    private ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext nodeBuilder;

    @BeforeEach
    void setUp() {
        context = mock(ConstraintValidatorContext.class);
        violationBuilder = mock(ConstraintValidatorContext.ConstraintViolationBuilder.class);
        nodeBuilder = mock(ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext.class);
        when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(violationBuilder);
        when(violationBuilder.addPropertyNode(anyString())).thenReturn(nodeBuilder);
    }

    @Test
    @DisplayName("should accept null DTO")
    void shouldAcceptNullDto() {
        assertTrue(validator.isValid(null, context));
    }

    @Nested
    @DisplayName("WebSocket endpoints")
    class WebSocketTests {

        @Test
        @DisplayName("should accept WebSocket endpoint with null methods")
        void shouldAcceptWebSocketWithNullMethods() {
            var dto = new EndpointConfigDto("/ws/echo", null, "PUBLIC", null, false, "WEBSOCKET", null, null, null);

            assertTrue(validator.isValid(dto, context));
        }

        @Test
        @DisplayName("should accept WebSocket endpoint with empty methods")
        void shouldAcceptWebSocketWithEmptyMethods() {
            var dto = new EndpointConfigDto(
                    "/ws/echo", Collections.emptySet(), "PUBLIC", null, false, "WEBSOCKET", null, null, null);

            assertTrue(validator.isValid(dto, context));
        }

        @Test
        @DisplayName("should accept case-insensitive WebSocket type")
        void shouldAcceptCaseInsensitiveWebSocketType() {
            var dto = new EndpointConfigDto("/ws/echo", null, "PUBLIC", null, false, "websocket", null, null, null);

            assertTrue(validator.isValid(dto, context));
        }
    }

    @Nested
    @DisplayName("HTTP endpoints")
    class HttpTests {

        @Test
        @DisplayName("should accept HTTP endpoint with methods")
        void shouldAcceptHttpWithMethods() {
            var dto = new EndpointConfigDto(
                    "/api/test", Set.of("GET", "POST"), "PUBLIC", null, false, "HTTP", null, null, null);

            assertTrue(validator.isValid(dto, context));
        }

        @Test
        @DisplayName("should accept endpoint with methods when type is null")
        void shouldAcceptNullTypeWithMethods() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, false, null, null, null, null);

            assertTrue(validator.isValid(dto, context));
        }

        @Test
        @DisplayName("should reject HTTP endpoint with null methods")
        void shouldRejectHttpWithNullMethods() {
            var dto = new EndpointConfigDto("/api/test", null, "PUBLIC", null, false, "HTTP", null, null, null);

            assertFalse(validator.isValid(dto, context));
            verify(context).disableDefaultConstraintViolation();
            verify(violationBuilder).addPropertyNode("methods");
        }

        @Test
        @DisplayName("should reject HTTP endpoint with empty methods")
        void shouldRejectHttpWithEmptyMethods() {
            var dto = new EndpointConfigDto(
                    "/api/test", Collections.emptySet(), "PUBLIC", null, false, "HTTP", null, null, null);

            assertFalse(validator.isValid(dto, context));
        }

        @Test
        @DisplayName("should reject endpoint with null methods when type is null")
        void shouldRejectNullTypeWithNullMethods() {
            var dto = new EndpointConfigDto("/api/test", null, "PUBLIC", null, false, null, null, null, null);

            assertFalse(validator.isValid(dto, context));
        }

        @Test
        @DisplayName("should not build custom violation for valid endpoint")
        void shouldNotBuildViolationForValidEndpoint() {
            var dto = new EndpointConfigDto("/api/test", Set.of("GET"), "PUBLIC", null, false, null, null, null, null);

            validator.isValid(dto, context);

            verify(context, never()).disableDefaultConstraintViolation();
        }
    }
}
