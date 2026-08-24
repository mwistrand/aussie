package aussie.adapter.in.health;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.microprofile.health.HealthCheckResponse.Status;
import org.junit.jupiter.api.Test;

import aussie.core.service.lifecycle.StartupState;

class GatewayReadinessHealthCheckTest {

    @Test
    void staysDownUntilRouteSnapshotAndSigningAreReady() {
        final var state = new StartupState();
        final var check = new GatewayReadinessHealthCheck(state);

        assertEquals(Status.DOWN, check.call().getStatus());
        state.complete(StartupState.Phase.CONFIG_VALIDATED);
        state.complete(StartupState.Phase.DEPENDENCIES_CONNECTED);
        state.complete(StartupState.Phase.SNAPSHOT_LOADED);
        assertEquals(Status.DOWN, check.call().getStatus());
        state.complete(StartupState.Phase.SIGNING_READY);
        assertEquals(Status.UP, check.call().getStatus());
    }

    @Test
    void reportsSafeFailureReason() {
        final var state = new StartupState();
        state.fail(StartupState.Failure.ROUTE_SNAPSHOT_UNAVAILABLE);

        final var response = new GatewayReadinessHealthCheck(state).call();

        assertEquals(Status.DOWN, response.getStatus());
        assertEquals(
                "route_snapshot_unavailable", response.getData().orElseThrow().get("reason"));
    }
}
