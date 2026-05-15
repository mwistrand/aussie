package aussie.adapter.in.problem;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.resteasy.problem.HttpProblem;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import aussie.core.service.auth.JwksCacheService.JwksFetchException;

/**
 * Global exception mappers for converting exceptions to RFC 9457 Problem Details.
 *
 * <p>These mappers prevent internal exceptions from returning 500 Internal Server Error
 * when a more appropriate status code should be used.
 */
@ApplicationScoped
public class GlobalExceptionMappers {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMappers.class);

    @ServerExceptionMapper
    public Response mapJwksFetchException(JwksFetchException e) {
        LOG.warnv("JWKS fetch failed: {0}", e.getMessage());
        return toResponse(GatewayProblem.badGateway("Identity provider unavailable"));
    }

    @ServerExceptionMapper
    public Response mapIllegalArgumentException(IllegalArgumentException e) {
        LOG.infov("Validation error: {0}", e.getMessage());
        return toResponse(GatewayProblem.validationError("Invalid request"));
    }

    @ServerExceptionMapper
    public Response mapIllegalStateException(IllegalStateException e) {
        LOG.infov("State error: {0}", e.getMessage());
        return toResponse(GatewayProblem.badRequest("Invalid request"));
    }

    private Response toResponse(HttpProblem problem) {
        return Response.status(problem.getStatusCode())
                .type(ProblemJson.CONTENT_TYPE)
                .entity(problem)
                .build();
    }
}
