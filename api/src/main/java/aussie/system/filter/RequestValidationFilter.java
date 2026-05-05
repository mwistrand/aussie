package aussie.system.filter;

import java.io.IOException;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

import aussie.adapter.in.problem.GatewayProblem;
import aussie.core.model.common.ValidationResult;
import aussie.core.service.common.RequestSizeValidator;

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
        final var contentLength = parseContentLength(requestContext);
        // MultivaluedMap<String, String> is a Map<String, List<String>>, so the validator
        // can read it without us materialising a defensive copy. The filter is the only
        // reader on this thread; the underlying request is read-only at this point.
        final var result = validator.validateRequest(contentLength, requestContext.getHeaders());

        if (result instanceof ValidationResult.Invalid invalid) {
            final var statusCode = invalid.suggestedStatusCode();
            final var reason = invalid.reason();

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
        final var contentLengthHeader = requestContext.getHeaderString("Content-Length");
        if (contentLengthHeader == null || contentLengthHeader.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(contentLengthHeader);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
