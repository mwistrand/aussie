package aussie.adapter.in.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.core.Response;

import io.quarkiverse.resteasy.problem.HttpProblem;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

class AdminMutationSupportTest {

    @Test
    void usesTheSharedVersionEtagConvention() {
        assertEquals("\"2\"", AdminMutationSupport.etag(2L));
        assertDoesNotThrow(() -> AdminMutationSupport.requireMatchingEtag("\"2\"", 2L));
        assertDoesNotThrow(() -> AdminMutationSupport.requireMatchingEtag("*", 2L));

        final var stale = assertThrows(HttpProblem.class, () -> AdminMutationSupport.requireMatchingEtag("\"1\"", 2L));
        assertEquals(Response.Status.PRECONDITION_FAILED.getStatusCode(), stale.getStatusCode());
    }

    @Test
    void tenantGuardRejectsCrossTenantAccess() {
        var identity = mock(SecurityIdentity.class);
        when(identity.getAttribute("teamId")).thenReturn("team-a");
        when(identity.getPrincipal()).thenReturn(() -> "user-a");

        assertEquals("team-a", AdminMutationSupport.requireTeam(identity, null));
        assertFalse(AdminMutationSupport.canSee(identity, "team-b"));
        assertThrows(HttpProblem.class, () -> AdminMutationSupport.requireTeam(identity, "team-b"));
    }

    @Test
    void idempotencyUsesStablePrincipalId() {
        var firstIdentity = identity("shared-name", "key-1");
        var secondIdentity = identity("shared-name", "key-2");

        var first = AdminMutationSupport.idempotent(
                        null,
                        firstIdentity,
                        "test.stable-principal",
                        "shared-key",
                        "same-request",
                        () -> Uni.createFrom().item(Response.ok("first").build()))
                .await()
                .indefinitely();
        var second = AdminMutationSupport.idempotent(
                        null,
                        secondIdentity,
                        "test.stable-principal",
                        "shared-key",
                        "same-request",
                        () -> Uni.createFrom().item(Response.ok("second").build()))
                .await()
                .indefinitely();

        assertEquals("first", first.getEntity());
        assertEquals("second", second.getEntity());
    }

    @Test
    void nonGlobalIdentityRequiresATeam() {
        var identity = identity("user", "user-1");

        assertThrows(HttpProblem.class, () -> AdminMutationSupport.requireTeam(identity, null));
    }

    private SecurityIdentity identity(String name, String principalId) {
        var identity = mock(SecurityIdentity.class);
        when(identity.getPrincipal()).thenReturn(() -> name);
        when(identity.getAttribute("principalId")).thenReturn(principalId);
        when(identity.getAttribute("authenticationMethod")).thenReturn("api_key");
        return identity;
    }
}
