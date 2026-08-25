package aussie.adapter.in.problem;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkiverse.resteasy.problem.HttpProblem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.core.service.auth.JwksCacheService.JwksFetchException;

@DisplayName("GlobalExceptionMappers")
class GlobalExceptionMappersTest {

    private final GlobalExceptionMappers mappers = new GlobalExceptionMappers();

    @Test
    @DisplayName("should map JwksFetchException to 502 Bad Gateway")
    void shouldMapJwksFetchExceptionTo502() {
        var problem = mappers.toProblem(new JwksFetchException("JWKS endpoint unreachable"));

        assertEquals(502, problem.getStatusCode());
        assertEquals("Identity provider unavailable", problem.getDetail());
        assertEquals("urn:aussie:problem:bad_gateway", problem.getType().toString());
        assertEquals("bad_gateway", problem.getParameters().get("code"));
    }

    @Test
    @DisplayName("should map unexpected exceptions to opaque 500 Internal Server Error")
    void shouldMapUnexpectedExceptionTo500() {
        var problem = mappers.toProblem(new IllegalStateException("secret implementation detail"));

        assertEquals(500, problem.getStatusCode());
        assertEquals("Internal server error", problem.getDetail());
        assertEquals("urn:aussie:problem:internal_error", problem.getType().toString());
        assertEquals("internal_error", problem.getParameters().get("code"));
    }

    @Test
    @DisplayName("should enrich framework problems with the Aussie type and code contract")
    void shouldEnrichFrameworkProblems() {
        var problem =
                HttpProblem.builder().withTitle("Bad Request").withStatus(400).build();

        var enriched = mappers.apply(problem, null);

        assertEquals("urn:aussie:problem:bad_request", enriched.getType().toString());
        assertEquals("bad_request", enriched.getParameters().get("code"));
    }
}
