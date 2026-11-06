package aussie.adapter.in.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import jakarta.enterprise.inject.Instance;

import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.health.HealthCheckResponse.Status;
import org.junit.jupiter.api.Test;

import aussie.core.config.PkceConfig;
import aussie.core.config.SessionConfig;
import aussie.core.model.common.StorageHealth;
import aussie.core.port.out.StorageHealthIndicator;
import aussie.core.service.auth.PkceStorageProviderRegistry;
import aussie.core.service.session.SessionStorageProviderRegistry;
import aussie.spi.PkceStorageProvider;

class DependencyReadinessHealthCheckTest {

    @Test
    void reportsUpWhenEveryDependencyIsHealthy() {
        final var indicator = indicator(StorageHealth.healthy("cassandra", 2));

        final var check = new DependencyReadinessHealthCheck(indicators(indicator));
        check.refreshScheduled();
        final var response = check.call();

        assertEquals(Status.UP, response.getStatus());
        assertEquals(1L, response.getData().orElseThrow().get("dependencies"));
        assertEquals(0L, response.getData().orElseThrow().get("failed"));
    }

    @Test
    void failsClosedWhenADependencyIsUnavailable() {
        final var indicator = indicator(StorageHealth.unhealthy("cassandra", "connection refused"));

        final var check = new DependencyReadinessHealthCheck(indicators(indicator));
        check.refreshScheduled();
        final var response = check.call();

        assertEquals(Status.DOWN, response.getStatus());
        assertEquals("dependency_unavailable", response.getData().orElseThrow().get("reason"));
        assertFalse(response.getData().orElseThrow().containsKey("message"));
    }

    @Test
    void failsClosedWhenIndicatorsCannotBeLoaded() {
        final var indicatorSets = mock(Instance.class);
        when(indicatorSets.iterator()).thenThrow(new IllegalStateException("producer failed"));

        final var check = new DependencyReadinessHealthCheck(indicatorSets);
        check.refreshScheduled();
        final var response = check.call();

        assertEquals(Status.DOWN, response.getStatus());
        assertEquals(1L, response.getData().orElseThrow().get("failed"));
        assertEquals("dependency_unavailable", response.getData().orElseThrow().get("reason"));
    }

    @Test
    void failsClosedWhenNoHealthIndicatorIsPublished() {
        final var check = new DependencyReadinessHealthCheck(indicators());
        check.refreshScheduled();
        final var response = check.call();

        assertEquals(Status.DOWN, response.getStatus());
        assertEquals("dependency_unavailable", response.getData().orElseThrow().get("reason"));
    }

    @Test
    void treatsUnsupportedProviderHealthAsAvailable() {
        final var sessionConfig = mock(SessionConfig.class);
        final var pkceConfig = mock(PkceConfig.class);
        when(pkceConfig.enabled()).thenReturn(true);
        final var provider = mock(PkceStorageProvider.class);
        when(provider.healthCheck()).thenReturn(Optional.empty());
        final var pkceRegistry = mock(PkceStorageProviderRegistry.class);
        when(pkceRegistry.getSelectedProvider()).thenReturn(provider);
        final var check = new DependencyReadinessHealthCheck(
                indicators(), mock(SessionStorageProviderRegistry.class), pkceRegistry, sessionConfig, pkceConfig);

        check.refreshScheduled();

        assertEquals(Status.UP, check.call().getStatus());
    }

    @SafeVarargs
    private static Instance<List<StorageHealthIndicator>> indicators(StorageHealthIndicator... indicators) {
        final var instance = mock(Instance.class);
        when(instance.iterator()).thenReturn(List.of(List.of(indicators)).iterator());
        return instance;
    }

    private static StorageHealthIndicator indicator(StorageHealth health) {
        final var indicator = mock(StorageHealthIndicator.class);
        when(indicator.check()).thenReturn(Uni.createFrom().item(health));
        return indicator;
    }
}
