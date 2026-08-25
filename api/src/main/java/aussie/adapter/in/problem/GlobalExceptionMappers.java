package aussie.adapter.in.problem;

import java.net.URI;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.ext.Provider;

import io.quarkiverse.resteasy.problem.ExceptionMapperBase;
import io.quarkiverse.resteasy.problem.HttpProblem;
import io.quarkiverse.resteasy.problem.postprocessing.ProblemContext;
import io.quarkiverse.resteasy.problem.postprocessing.ProblemPostProcessor;

import aussie.core.service.auth.JwksCacheService.JwksFetchException;

/**
 * Global fallback mapper and contract enricher for RFC 9457 Problem Details.
 *
 * <p>Specific mappers, including {@code HttpProblemMapper}, still take precedence.
 * Unknown failures remain opaque 500 responses so implementation exceptions
 * cannot become 400s.
 */
@ApplicationScoped
@Provider
@Priority(Priorities.USER - 1)
public class GlobalExceptionMappers extends ExceptionMapperBase<Exception> implements ProblemPostProcessor {

    @Override
    protected HttpProblem toProblem(Exception exception) {
        if (exception instanceof JwksFetchException) {
            return GatewayProblem.badGateway("Identity provider unavailable");
        }
        return GatewayProblem.internalError("Internal server error");
    }

    @Override
    public HttpProblem apply(HttpProblem problem, ProblemContext context) {
        final var existingCode = problem.getParameters().get("code");
        if (problem.getType() != null && existingCode instanceof String value && !value.isBlank()) {
            return problem;
        }

        final var code = existingCode instanceof String value && !value.isBlank()
                ? value
                : problem.getTitle() == null || problem.getTitle().isBlank()
                        ? "http_" + problem.getStatusCode()
                        : ProblemDetail.codeFor(problem.getTitle());
        final var builder = HttpProblem.builder(problem);
        if (problem.getType() == null) {
            builder.withType(URI.create("urn:aussie:problem:" + code));
        }
        if (!(existingCode instanceof String value) || value.isBlank()) {
            builder.with("code", code);
        }
        return builder.build();
    }
}
