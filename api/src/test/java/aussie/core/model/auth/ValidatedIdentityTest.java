package aussie.core.model.auth;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ValidatedIdentityTest {

    @Test
    void exposesNoPublicConstructor() {
        assertEquals(0, ValidatedIdentity.class.getConstructors().length);
        assertTrue(Arrays.stream(ValidatedIdentity.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())));
    }

    @Test
    void preservesValidatedIdentityDataAndValueSemantics() {
        final var authenticatedAt = Optional.of(Instant.EPOCH);
        final var expiresAt = Instant.MAX;
        final var claims = new HashMap<String, Object>(Map.of("sub", "subject"));
        final var identity = ValidatedIdentity.fromValidatedClaims(
                "provider",
                "subject",
                "issuer",
                Set.of("audience"),
                authenticatedAt,
                Optional.of("token"),
                claims,
                Optional.of("high"),
                expiresAt);
        claims.put("sub", "changed");
        final var sameIdentity = identity.withClaims(identity.claims());

        assertAll(
                () -> assertEquals("provider", identity.providerId()),
                () -> assertEquals("subject", identity.subject()),
                () -> assertEquals("issuer", identity.issuer()),
                () -> assertEquals(Set.of("audience"), identity.audiences()),
                () -> assertEquals(authenticatedAt, identity.authenticatedAt()),
                () -> assertEquals(Optional.of("token"), identity.tokenId()),
                () -> assertEquals(Map.of("sub", "subject"), identity.claims()),
                () -> assertEquals(Optional.of("high"), identity.assuranceLevel()),
                () -> assertEquals(expiresAt, identity.expiresAt()),
                () -> assertEquals(identity, sameIdentity),
                () -> assertEquals(identity.hashCode(), sameIdentity.hashCode()),
                () -> assertThrows(UnsupportedOperationException.class, () -> identity.claims()
                        .put("sub", "changed")));
    }
}
