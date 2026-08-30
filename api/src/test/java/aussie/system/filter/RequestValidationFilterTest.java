package aussie.system.filter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import io.quarkiverse.httpproblem.HttpProblem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.model.common.ValidationResult;
import aussie.core.service.common.RequestSizeValidator;

@DisplayName("RequestValidationFilter")
@ExtendWith(MockitoExtension.class)
class RequestValidationFilterTest {

    @Mock
    private RequestSizeValidator validator;

    @Mock
    private ContainerRequestContext requestContext;

    private RequestValidationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new RequestValidationFilter(validator);
    }

    private void setupHeaders(MultivaluedMap<String, String> headers) {
        when(requestContext.getHeaders()).thenReturn(headers);
    }

    private MultivaluedMap<String, String> emptyHeaders() {
        return new MultivaluedHashMap<>();
    }

    @Nested
    @DisplayName("Validation results")
    class ValidationResults {

        @Test
        @DisplayName("should not throw when validation passes")
        void validResult() {
            when(requestContext.getHeaderString("Content-Length")).thenReturn("100");
            var headers = emptyHeaders();
            setupHeaders(headers);
            when(validator.validateRequest(anyLong(), anyMap())).thenReturn(ValidationResult.valid());

            assertDoesNotThrow(() -> filter.filter(requestContext));
        }

        @Test
        @DisplayName("should throw HttpProblem with 413 for payload too large")
        void invalid413() {
            when(requestContext.getHeaderString("Content-Length")).thenReturn("999999999");
            var headers = emptyHeaders();
            setupHeaders(headers);
            when(validator.validateRequest(anyLong(), anyMap()))
                    .thenReturn(ValidationResult.invalid("Body too large", 413));

            var ex = assertThrows(HttpProblem.class, () -> filter.filter(requestContext));
            assertEquals(413, ex.getStatusCode());
        }

        @Test
        @DisplayName("should throw HttpProblem with 431 for header too large")
        void invalid431() {
            when(requestContext.getHeaderString("Content-Length")).thenReturn("0");
            var headers = emptyHeaders();
            setupHeaders(headers);
            when(validator.validateRequest(anyLong(), anyMap()))
                    .thenReturn(ValidationResult.invalid("Header too large", 431));

            var ex = assertThrows(HttpProblem.class, () -> filter.filter(requestContext));
            assertEquals(431, ex.getStatusCode());
        }

        @Test
        @DisplayName("should throw HttpProblem with 400 for other invalid status codes")
        void invalidOther() {
            when(requestContext.getHeaderString("Content-Length")).thenReturn("0");
            var headers = emptyHeaders();
            setupHeaders(headers);
            when(validator.validateRequest(anyLong(), anyMap()))
                    .thenReturn(ValidationResult.invalid("Bad request", 400));

            var ex = assertThrows(HttpProblem.class, () -> filter.filter(requestContext));
            assertEquals(400, ex.getStatusCode());
        }
    }

    @Nested
    @DisplayName("Content-Length parsing")
    class ContentLengthParsing {

        @Test
        @DisplayName("should parse missing Content-Length as 0")
        void missingContentLength() {
            when(requestContext.getHeaderString("Content-Length")).thenReturn(null);
            var headers = emptyHeaders();
            setupHeaders(headers);
            when(validator.validateRequest(0L, Map.of())).thenReturn(ValidationResult.valid());

            assertDoesNotThrow(() -> filter.filter(requestContext));
        }

        @Test
        @DisplayName("should parse non-numeric Content-Length as 0")
        void nonNumericContentLength() {
            when(requestContext.getHeaderString("Content-Length")).thenReturn("not-a-number");
            var headers = emptyHeaders();
            setupHeaders(headers);
            when(validator.validateRequest(0L, Map.of())).thenReturn(ValidationResult.valid());

            assertDoesNotThrow(() -> filter.filter(requestContext));
        }

        @Test
        @DisplayName("should parse valid Content-Length correctly")
        void validContentLength() {
            when(requestContext.getHeaderString("Content-Length")).thenReturn("12345");
            var headers = new MultivaluedHashMap<String, String>();
            headers.put("Content-Type", List.of("application/json"));
            setupHeaders(headers);
            when(validator.validateRequest(12345L, Map.of("Content-Type", List.of("application/json"))))
                    .thenReturn(ValidationResult.valid());

            assertDoesNotThrow(() -> filter.filter(requestContext));
        }

        @Test
        @DisplayName("should parse empty Content-Length as 0")
        void emptyContentLength() {
            when(requestContext.getHeaderString("Content-Length")).thenReturn("");
            var headers = emptyHeaders();
            setupHeaders(headers);
            when(validator.validateRequest(0L, Map.of())).thenReturn(ValidationResult.valid());

            assertDoesNotThrow(() -> filter.filter(requestContext));
        }
    }
}
