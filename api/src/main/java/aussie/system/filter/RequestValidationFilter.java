package aussie.system.filter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.model.ValidationResult;
import aussie.core.service.RequestSizeValidator;

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
            int statusCode = invalid.suggestedStatusCode();
            String reason = invalid.reason();

            if (statusCode == 413) {
                throw GatewayProblem.payloadTooLarge(reason);
            } else if (statusCode == 431) {
                throw GatewayProblem.headerTooLarge(reason);
            } else {
                throw GatewayProblem.badRequest(reason);
            }
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
