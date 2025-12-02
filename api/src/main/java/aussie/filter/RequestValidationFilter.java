package aussie.filter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import aussie.validation.RequestSizeValidator;
import aussie.validation.ValidationResult;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

@Provider
@Priority(Priorities.AUTHENTICATION - 100)
public class RequestValidationFilter implements ContainerRequestFilter {

    private final RequestSizeValidator validator;

    @Inject
    public RequestValidationFilter(RequestSizeValidator validator) {
        this.validator = validator;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        var contentLength = parseContentLength(requestContext);
        var headers = extractHeaders(requestContext);

        var result = validator.validateRequest(contentLength, headers);

        if (result instanceof ValidationResult.Invalid invalid) {
            requestContext.abortWith(
                Response.status(invalid.suggestedStatusCode())
                    .entity(invalid.reason())
                    .build()
            );
        }
    }

    private long parseContentLength(ContainerRequestContext requestContext) {
        var contentLengthHeader = requestContext.getHeaderString("Content-Length");
        if (contentLengthHeader == null || contentLengthHeader.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(contentLengthHeader);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Map<String, List<String>> extractHeaders(ContainerRequestContext requestContext) {
        Map<String, List<String>> headers = new HashMap<>();
        for (var headerName : requestContext.getHeaders().keySet()) {
            headers.put(headerName, new ArrayList<>(requestContext.getHeaders().get(headerName)));
        }
        return headers;
    }
}
