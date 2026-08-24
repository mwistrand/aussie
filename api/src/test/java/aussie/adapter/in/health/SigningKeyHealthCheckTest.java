package aussie.adapter.in.health;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.core.config.KeyRotationConfig;
import aussie.core.config.RouteAuthConfig;
import aussie.core.config.SessionConfig;
import aussie.core.service.auth.SigningKeyRegistry;
import aussie.core.service.lifecycle.StartupState;
import aussie.spi.SigningKeyRepository;

@DisplayName("SigningKeyHealthCheck")
class SigningKeyHealthCheckTest {

    private SigningKeyRegistry keyRegistry;
    private KeyRotationConfig rotationConfig;
    private RouteAuthConfig routeAuthConfig;
    private SessionConfig sessionConfig;
    private SigningKeyRepository keyRepository;
    private StartupState startupState;
    private SigningKeyHealthCheck healthCheck;

    @BeforeEach
    void setUp() {
        keyRegistry = mock(SigningKeyRegistry.class);
        rotationConfig = mock(KeyRotationConfig.class);
        routeAuthConfig = mock(RouteAuthConfig.class);
        sessionConfig = mock(SessionConfig.class);
        keyRepository = mock(SigningKeyRepository.class);
        startupState = new StartupState();
        final var routeJws = mock(RouteAuthConfig.JwsProperties.class);
        final var sessionJws = mock(SessionConfig.JwsConfig.class);

        when(routeAuthConfig.jws()).thenReturn(routeJws);
        when(sessionConfig.jws()).thenReturn(sessionJws);
        when(routeJws.tokenTtl()).thenReturn(Duration.ofMinutes(5));
        when(routeJws.maxTokenTtl()).thenReturn(Duration.ofHours(24));
        when(routeJws.requireAudience()).thenReturn(true);
        when(sessionJws.ttl()).thenReturn(Duration.ofMinutes(5));
        when(sessionJws.audience()).thenReturn(Optional.of("downstream-services"));
        when(rotationConfig.deprecationPeriod()).thenReturn(Duration.ofDays(7));
        when(rotationConfig.keySize()).thenReturn(2048);
        when(keyRepository.isDurable()).thenReturn(true);
        when(keyRegistry.refreshCache()).thenReturn(Uni.createFrom().voidItem());

        healthCheck = new SigningKeyHealthCheck(
                keyRegistry,
                rotationConfig,
                routeAuthConfig,
                sessionConfig,
                keyRepository,
                new SimpleMeterRegistry(),
                startupState);
    }

    @Test
    @DisplayName("startup loads the authority before accepting issuance")
    void startupLoadsAuthority() {
        when(routeAuthConfig.enabled()).thenReturn(true);
        when(keyRegistry.isReady()).thenReturn(true);

        healthCheck.onStart(mock(StartupEvent.class));

        verify(keyRegistry).refreshCache();
        assertTrue(startupState.snapshot().failure().isEmpty());
        assertEquals(HealthCheckResponse.Status.UP, healthCheck.call().getStatus());
    }

    @Test
    @DisplayName("readiness is down when issuance has no published active key")
    void readinessFailsClosed() {
        when(routeAuthConfig.enabled()).thenReturn(true);
        when(keyRegistry.isReady()).thenReturn(false);

        assertEquals(HealthCheckResponse.Status.DOWN, healthCheck.call().getStatus());
        assertThrows(IllegalStateException.class, () -> healthCheck.onStart(mock(StartupEvent.class)));
        assertEquals(
                Optional.of("signing_key_unavailable"), startupState.snapshot().failure());
    }

    @Test
    @DisplayName("normal mode rejects process-local key rotation")
    void normalModeRejectsProcessLocalRotation() {
        when(rotationConfig.enabled()).thenReturn(true);
        when(keyRepository.isDurable()).thenReturn(false);

        final var error =
                assertThrows(IllegalStateException.class, () -> healthCheck.validateConfiguration(LaunchMode.NORMAL));

        assertTrue(error.getMessage().contains("durable repository"));
    }

    @Test
    @DisplayName("deprecation period covers only effective lifetimes from enabled profiles")
    void validatesEffectiveTokenLifetime() {
        when(rotationConfig.enabled()).thenReturn(true);
        when(rotationConfig.deprecationPeriod()).thenReturn(Duration.ofMinutes(10));
        when(routeAuthConfig.enabled()).thenReturn(true);

        assertDoesNotThrow(() -> healthCheck.validateConfiguration(LaunchMode.TEST));

        when(routeAuthConfig.enabled()).thenReturn(false);
        when(sessionConfig.enabled()).thenReturn(true);
        when(sessionConfig.jws().enabled()).thenReturn(true);
        when(sessionConfig.jws().ttl()).thenReturn(Duration.ofMinutes(15));

        assertThrows(IllegalStateException.class, () -> healthCheck.validateConfiguration(LaunchMode.TEST));
    }

    @Test
    @DisplayName("blank audiences are rejected")
    void rejectsBlankAudience() {
        when(routeAuthConfig.enabled()).thenReturn(true);
        when(routeAuthConfig.jws().requireAudience()).thenReturn(false);
        when(routeAuthConfig.jws().defaultAudience()).thenReturn(Optional.of(" "));

        assertThrows(IllegalStateException.class, () -> healthCheck.validateConfiguration(LaunchMode.TEST));
    }
}
