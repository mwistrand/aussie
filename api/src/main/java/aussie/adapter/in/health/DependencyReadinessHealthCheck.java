package aussie.adapter.in.health;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduled.ConcurrentExecution;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import aussie.core.port.out.StorageHealthIndicator;

/** Fails readiness when a configured storage dependency stops responding. */
@Readiness
@ApplicationScoped
public class DependencyReadinessHealthCheck implements HealthCheck {

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(1);

    private final Instance<List<StorageHealthIndicator>> indicatorSets;
    private final AtomicReference<DependencyState> state = new AtomicReference<>(DependencyState.PENDING);

    @Inject
    public DependencyReadinessHealthCheck(Instance<List<StorageHealthIndicator>> indicatorSets) {
        this.indicatorSets = indicatorSets;
    }

    void onStart(@Observes @Priority(100) StartupEvent event) {
        refresh();
    }

    @Scheduled(every = "5s", concurrentExecution = ConcurrentExecution.SKIP)
    void refreshScheduled() {
        refresh();
    }

    @Override
    public HealthCheckResponse call() {
        final var current = state.get();
        final var builder = HealthCheckResponse.named("required-dependencies")
                .withData("dependencies", current.dependencies())
                .withData("failed", current.failed());
        if (current.reason() != null) {
            builder.withData("reason", current.reason());
        }
        return current.failed() == 0 && current.dependencies() > 0 && current.reason() == null
                ? builder.up().build()
                : builder.down().build();
    }

    private void refresh() {
        var dependencyCount = 0;
        var failedCount = 0;
        try {
            for (final var indicators : indicatorSets) {
                for (final var indicator : indicators) {
                    dependencyCount++;
                    try {
                        if (!indicator.check().await().atMost(CHECK_TIMEOUT).healthy()) {
                            failedCount++;
                        }
                    } catch (RuntimeException e) {
                        failedCount++;
                    }
                }
            }
        } catch (RuntimeException e) {
            failedCount++;
        }
        state.set(new DependencyState(
                dependencyCount,
                failedCount,
                dependencyCount == 0 || failedCount > 0 ? "dependency_unavailable" : null));
    }

    private record DependencyState(int dependencies, int failed, String reason) {
        private static final DependencyState PENDING = new DependencyState(0, 0, "dependency_check_pending");
    }
}
