package aussie.system.filter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;

import aussie.adapter.in.problem.ProblemDetail;
import aussie.adapter.in.vertx.ProxyErrorWriter;
import aussie.core.model.common.ValidationResult;
import aussie.core.service.common.RequestSizeValidator;

/** Reject ambiguous request framing before route dispatch or body decoding. */
@ApplicationScoped
public class TransportRequestValidationFilter {

    private final ProxyErrorWriter errorWriter;
    private final RequestSizeValidator validator;

    @Inject
    public TransportRequestValidationFilter(ProxyErrorWriter errorWriter, RequestSizeValidator validator) {
        this.errorWriter = errorWriter;
        this.validator = validator;
    }

    @RouteFilter(110)
    void validate(RoutingContext context) {
        final var headers = context.request().headers();
        final var headerResult = validator.validateHeaders(headers);
        if (headerResult instanceof ValidationResult.Invalid invalid) {
            errorWriter.write(context, ProblemDetail.headerTooLarge(invalid.reason()));
            return;
        }

        final var contentLengths = headers.getAll(HttpHeaders.CONTENT_LENGTH);
        if (contentLengths.size() > 1) {
            reject(context, "Multiple Content-Length headers are not allowed");
            return;
        }
        if (contentLengths.isEmpty()) {
            context.next();
            return;
        }

        final var contentLength = contentLengths.getFirst().trim();
        if (!isUnsignedDecimal(contentLength)) {
            reject(context, "Content-Length must be a non-negative decimal value");
            return;
        }
        try {
            Long.parseLong(contentLength);
        } catch (NumberFormatException ignored) {
            reject(context, "Content-Length is out of range");
            return;
        }

        if (headers.contains(HttpHeaders.TRANSFER_ENCODING)) {
            reject(context, "Content-Length cannot be combined with Transfer-Encoding");
            return;
        }
        context.next();
    }

    private void reject(RoutingContext context, String detail) {
        errorWriter.write(context, ProblemDetail.badRequest(detail));
    }

    private static boolean isUnsignedDecimal(String value) {
        return value != null
                && !value.isEmpty()
                && value.chars().allMatch(character -> character >= '0' && character <= '9');
    }
}
