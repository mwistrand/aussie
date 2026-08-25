package aussie.core.service.lifecycle;

import java.util.EnumSet;
import java.util.Optional;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.runtime.ShutdownDelayInitiatedEvent;
import io.quarkus.runtime.ShutdownEvent;

/** Tracks the small set of startup conditions required before serving traffic. */
@ApplicationScoped
public class StartupState {

    public enum Phase {
        CONFIG_VALIDATED,
        DEPENDENCIES_CONNECTED,
        SNAPSHOT_LOADED,
        SIGNING_READY
    }

    public enum Failure {
        CONFIGURATION_INVALID("configuration_invalid"),
        ROUTE_SNAPSHOT_UNAVAILABLE("route_snapshot_unavailable"),
        SIGNING_KEY_UNAVAILABLE("signing_key_unavailable");

        private final String code;

        Failure(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final EnumSet<Phase> completed = EnumSet.noneOf(Phase.class);
    private Failure failure;
    private boolean draining;

    public synchronized void complete(Phase phase) {
        if (failure == null && !draining) {
            completed.add(phase);
        }
    }

    public synchronized void fail(Failure failure) {
        if (!draining && this.failure == null) {
            this.failure = failure;
        }
    }

    public synchronized boolean isReady() {
        return failure == null
                && !draining
                && completed.contains(Phase.CONFIG_VALIDATED)
                && completed.contains(Phase.DEPENDENCIES_CONNECTED)
                && completed.contains(Phase.SNAPSHOT_LOADED)
                && completed.contains(Phase.SIGNING_READY);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(phase(), Optional.ofNullable(failure).map(Failure::code), isReady());
    }

    void onShutdown(@Observes @Priority(1) ShutdownEvent event) {
        drain();
    }

    void onShutdownDelayInitiated(@Observes ShutdownDelayInitiatedEvent event) {
        drain();
    }

    @PreDestroy
    synchronized void drain() {
        draining = true;
    }

    private String phase() {
        if (draining) {
            return "DRAINING";
        }
        if (failure != null) {
            return "FAILED";
        }
        if (isReady()) {
            return "READY";
        }
        if (completed.contains(Phase.SNAPSHOT_LOADED)) {
            return "SNAPSHOT_LOADED";
        }
        if (completed.contains(Phase.DEPENDENCIES_CONNECTED)) {
            return "DEPENDENCIES_CONNECTED";
        }
        if (completed.contains(Phase.CONFIG_VALIDATED)) {
            return "CONFIG_VALIDATED";
        }
        return "STARTING";
    }

    public record Snapshot(String phase, Optional<String> failure, boolean ready) {}
}
