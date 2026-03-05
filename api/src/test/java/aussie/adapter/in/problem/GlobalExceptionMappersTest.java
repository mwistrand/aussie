package aussie.adapter.in.problem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.core.service.auth.JwksCacheService.JwksFetchException;

@DisplayName("GlobalExceptionMappers")
class GlobalExceptionMappersTest {

    private final GlobalExceptionMappers mappers = new GlobalExceptionMappers();

    @Test
    @DisplayName("should map JwksFetchException to 502 Bad Gateway")
    void shouldMapJwksFetchExceptionTo502() {
        var response = mappers.mapJwksFetchException(new JwksFetchException("JWKS endpoint unreachable"));

        assertEquals(502, response.getStatus());
        assertEquals("application/problem+json", response.getMediaType().toString());
        assertNotNull(response.getEntity());
    }

    @Test
    @DisplayName("should map IllegalArgumentException to 400 Bad Request")
    void shouldMapIllegalArgumentTo400() {
        var response = mappers.mapIllegalArgumentException(new IllegalArgumentException("Invalid field"));

        assertEquals(400, response.getStatus());
        assertEquals("application/problem+json", response.getMediaType().toString());
    }

    @Test
    @DisplayName("should map IllegalStateException to 400 Bad Request")
    void shouldMapIllegalStateTo400() {
        var response = mappers.mapIllegalStateException(new IllegalStateException("Bad state"));

        assertEquals(400, response.getStatus());
        assertEquals("application/problem+json", response.getMediaType().toString());
    }
}
