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

import aussie.core.config.PkceConfig;
import aussie.core.config.SessionConfig;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.core.service.auth.PkceStorageProviderRegistry;
import aussie.core.service.session.SessionStorageProviderRegistry;

/** Fails readiness when a configured storage dependency stops responding. */
@Readiness
@ApplicationScoped
public class DependencyReadinessHealthCheck implements HealthCheck {

    private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(1);

    private final Instance<List<StorageHealthIndicator>> indicatorSets;
    private final SessionStorageProviderRegistry sessionRegistry;
    private final PkceStorageProviderRegistry pkceRegistry;
    private final SessionConfig sessionConfig;
    private final PkceConfig pkceConfig;
    private final AtomicReference<DependencyState> state = new AtomicReference<>(DependencyState.PENDING);

    @Inject
    public DependencyReadinessHealthCheck(
            Instance<List<StorageHealthIndicator>> indicatorSets,
            SessionStorageProviderRegistry sessionRegistry,
            PkceStorageProviderRegistry pkceRegistry,
            SessionConfig sessionConfig,
            PkceConfig pkceConfig) {
        this.indicatorSets = indicatorSets;
        this.sessionRegistry = sessionRegistry;
        this.pkceRegistry = pkceRegistry;
        this.sessionConfig = sessionConfig;
        this.pkceConfig = pkceConfig;
    }

    public DependencyReadinessHealthCheck(Instance<List<StorageHealthIndicator>> indicatorSets) {
        this(indicatorSets, null, null, null, null);
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
            if (sessionConfig != null && sessionConfig.enabled()) {
                dependencyCount++;
                if (sessionRegistry
                        .getSelectedProvider()
                        .healthCheck()
                        .map(this::isDown)
                        .orElse(false)) {
                    failedCount++;
                }
            }
            if (pkceConfig != null && pkceConfig.enabled()) {
                dependencyCount++;
                if (pkceRegistry
                        .getSelectedProvider()
                        .healthCheck()
                        .map(this::isDown)
                        .orElse(false)) {
                    failedCount++;
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

    private boolean isDown(HealthCheckResponse response) {
        return response.getStatus() != HealthCheckResponse.Status.UP;
    }

    private record DependencyState(int dependencies, int failed, String reason) {
        private static final DependencyState PENDING = new DependencyState(0, 0, "dependency_check_pending");
    }
}
