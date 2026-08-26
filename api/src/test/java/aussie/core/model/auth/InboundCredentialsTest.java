package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class InboundCredentialsTest {

    @Test
    void parsesHeadersCaseInsensitivelyAndRejectsConflicts() {
        final var credentials = InboundCredentials.from(
                Map.of(
                        "authorization", List.of("bearer token"),
                        "COOKIE", List.of("other=value; aussie_session=session-1")),
                "aussie_session");

        assertTrue(credentials.hasConflictingCredentials());
        assertEquals("token", credentials.bearerToken().orElseThrow());
        assertEquals("session-1", credentials.sessionId().orElseThrow());
    }

    @Test
    void doesNotTreatSimilarCookieNamesAsTheSession() {
        final var credentials =
                InboundCredentials.from(Map.of("Cookie", List.of("aussie_session_backup=session-1")), "aussie_session");

        assertFalse(credentials.sessionId().isPresent());
    }

    @Test
    void rejectsMultipleAuthorizationHeadersBeforeParsing() {
        final var credentials = new InboundCredentials(List.of("Bearer one", "Bearer two"), null);

        assertTrue(credentials.hasConflictingCredentials());
        assertTrue(credentials.bearerToken().isEmpty());
    }
}
