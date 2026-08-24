package aussie.adapter.in.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import aussie.core.service.lifecycle.StartupState;

/** Reports readiness only after required startup invariants are established. */
@Readiness
@ApplicationScoped
public class GatewayReadinessHealthCheck implements HealthCheck {

    private final StartupState startupState;

    @Inject
    public GatewayReadinessHealthCheck(StartupState startupState) {
        this.startupState = startupState;
    }

    @Override
    public HealthCheckResponse call() {
        final var state = startupState.snapshot();
        final var builder = HealthCheckResponse.named("gateway-startup")
                .withData("phase", state.phase())
                .withData("ready", state.ready());
        state.failure().ifPresent(reason -> builder.withData("reason", reason));
        return state.ready() ? builder.up().build() : builder.down().build();
    }
}
