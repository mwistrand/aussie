package aussie.system.filter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.problem.ProblemDetail;
import aussie.adapter.in.vertx.ProxyErrorWriter;
import aussie.core.model.common.ValidationResult;
import aussie.core.service.common.RequestSizeValidator;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransportRequestValidationFilter")
class TransportRequestValidationFilterTest {

    @Mock
    private ProxyErrorWriter errorWriter;

    @Mock
    private RequestSizeValidator validator;

    @Mock
    private RoutingContext context;

    @Mock
    private HttpServerRequest request;

    @Mock
    private MultiMap headers;

    private TransportRequestValidationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new TransportRequestValidationFilter(errorWriter, validator);
        when(context.request()).thenReturn(request);
        when(request.headers()).thenReturn(headers);
        when(validator.validateHeaders(headers)).thenReturn(ValidationResult.valid());
    }

    @Test
    void acceptsMissingContentLength() {
        when(headers.getAll(HttpHeaders.CONTENT_LENGTH)).thenReturn(List.of());

        assertDoesNotThrow(() -> filter.validate(context));

        verify(context).next();
    }

    @Test
    void rejectsMalformedContentLength() {
        when(headers.getAll(HttpHeaders.CONTENT_LENGTH)).thenReturn(List.of("not-a-number"));

        filter.validate(context);

        verify(errorWriter).write(eq(context), any(ProblemDetail.class));
        verify(context, never()).next();
    }

    @Test
    void rejectsOversizedHeaderBeforeFramingValidation() {
        when(validator.validateHeaders(headers)).thenReturn(ValidationResult.headerTooLarge("header too large"));

        filter.validate(context);

        verify(errorWriter).write(eq(context), any(ProblemDetail.class));
        verify(context, never()).next();
        verify(headers, never()).getAll(HttpHeaders.CONTENT_LENGTH);
    }

    @Test
    void rejectsDuplicateContentLength() {
        when(headers.getAll(HttpHeaders.CONTENT_LENGTH)).thenReturn(List.of("10", "10"));

        filter.validate(context);

        verify(errorWriter).write(eq(context), any(ProblemDetail.class));
        verify(context, never()).next();
    }

    @Test
    void rejectsContentLengthWithTransferEncoding() {
        when(headers.getAll(HttpHeaders.CONTENT_LENGTH)).thenReturn(List.of("10"));
        when(headers.contains(HttpHeaders.TRANSFER_ENCODING)).thenReturn(true);

        filter.validate(context);

        verify(errorWriter).write(eq(context), any(ProblemDetail.class));
        verify(context, never()).next();
    }
}
