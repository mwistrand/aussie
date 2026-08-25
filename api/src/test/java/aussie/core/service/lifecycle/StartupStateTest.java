package aussie.core.service.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import io.quarkus.runtime.ShutdownDelayInitiatedEvent;
import io.quarkus.runtime.ShutdownEvent;
import org.junit.jupiter.api.Test;

class StartupStateTest {

    @Test
    void requiresEveryStartupCondition() {
        final var state = new StartupState();
        state.complete(StartupState.Phase.CONFIG_VALIDATED);
        state.complete(StartupState.Phase.SNAPSHOT_LOADED);
        state.complete(StartupState.Phase.SIGNING_READY);

        assertFalse(state.isReady());

        state.complete(StartupState.Phase.DEPENDENCIES_CONNECTED);

        assertTrue(state.isReady());
    }

    @Test
    void preservesTheFirstFailureReason() {
        final var state = new StartupState();
        state.fail(StartupState.Failure.ROUTE_SNAPSHOT_UNAVAILABLE);
        state.fail(StartupState.Failure.SIGNING_KEY_UNAVAILABLE);

        assertEquals(Optional.of("route_snapshot_unavailable"), state.snapshot().failure());
    }

    @Test
    void becomesUnreadyBeforeApplicationScopedResourcesAreDestroyed() {
        final var state = new StartupState();
        for (final var phase : StartupState.Phase.values()) {
            state.complete(phase);
        }
        assertTrue(state.isReady());

        state.onShutdown(new ShutdownEvent());

        assertFalse(state.isReady());
        assertEquals("DRAINING", state.snapshot().phase());
    }

    @Test
    void becomesUnreadyWhenShutdownDelayBegins() {
        final var state = new StartupState();
        for (final var phase : StartupState.Phase.values()) {
            state.complete(phase);
        }

        state.onShutdownDelayInitiated(new ShutdownDelayInitiatedEvent());

        assertFalse(state.isReady());
        assertEquals("DRAINING", state.snapshot().phase());
    }
}
