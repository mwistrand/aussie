package aussie.adapter.in.health;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import aussie.core.config.KeyRotationConfig;
import aussie.core.config.RouteAuthConfig;
import aussie.core.config.SessionConfig;
import aussie.core.service.auth.SigningKeyRegistry;
import aussie.spi.SigningKeyRepository;

/** Initializes the signing authority and keeps issuance readiness fail-closed. */
@Readiness
@ApplicationScoped
public class SigningKeyHealthCheck implements HealthCheck {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(10);

    private final SigningKeyRegistry keyRegistry;
    private final KeyRotationConfig rotationConfig;
    private final RouteAuthConfig routeAuthConfig;
    private final SessionConfig sessionConfig;
    private final SigningKeyRepository keyRepository;

    @Inject
    public SigningKeyHealthCheck(
            SigningKeyRegistry keyRegistry,
            KeyRotationConfig rotationConfig,
            RouteAuthConfig routeAuthConfig,
            SessionConfig sessionConfig,
            SigningKeyRepository keyRepository,
            MeterRegistry meterRegistry) {
        this.keyRegistry = keyRegistry;
        this.rotationConfig = rotationConfig;
        this.routeAuthConfig = routeAuthConfig;
        this.sessionConfig = sessionConfig;
        this.keyRepository = keyRepository;
        Gauge.builder("aussie.signing.key.ready", this, check -> check.signingReady() ? 1 : 0)
                .description("Whether required token issuance has one active key published in JWKS")
                .register(meterRegistry);
    }

    void onStart(@Observes StartupEvent event) {
        validateConfiguration(LaunchMode.current());
        keyRegistry.refreshCache().await().atMost(STARTUP_TIMEOUT);
        if (issuanceRequired() && !keyRegistry.isReady()) {
            throw new IllegalStateException("Token issuance requires one active signing key published in JWKS");
        }
    }

    void validateConfiguration(LaunchMode launchMode) {
        if (rotationConfig.enabled() && !keyRepository.isDurable() && launchMode == LaunchMode.NORMAL) {
            throw new IllegalStateException("Rotating signing keys require a durable repository outside dev/test");
        }
        if (rotationConfig.enabled() && rotationConfig.keySize() < 2048) {
            throw new IllegalStateException("RSA signing keys must be at least 2048 bits");
        }

        var maximumTokenLifetime = Duration.ZERO;
        if (routeAuthConfig.enabled()) {
            final var routeTtl = routeAuthConfig
                                    .jws()
                                    .tokenTtl()
                                    .compareTo(routeAuthConfig.jws().maxTokenTtl())
                            <= 0
                    ? routeAuthConfig.jws().tokenTtl()
                    : routeAuthConfig.jws().maxTokenTtl();
            maximumTokenLifetime = routeTtl;
        }
        if (sessionConfig.enabled()
                && sessionConfig.jws().enabled()
                && sessionConfig.jws().ttl().compareTo(maximumTokenLifetime) > 0) {
            maximumTokenLifetime = sessionConfig.jws().ttl();
        }
        if (rotationConfig.enabled() && rotationConfig.deprecationPeriod().compareTo(maximumTokenLifetime) < 0) {
            throw new IllegalStateException("Signing-key deprecation period must cover the maximum token lifetime");
        }
        if (routeAuthConfig.enabled()
                && !routeAuthConfig.jws().requireAudience()
                && routeAuthConfig
                        .jws()
                        .defaultAudience()
                        .filter(value -> !value.isBlank())
                        .isEmpty()) {
            throw new IllegalStateException("Route token issuance requires audience binding");
        }
        if (sessionConfig.enabled()
                && sessionConfig.jws().enabled()
                && sessionConfig
                        .jws()
                        .audience()
                        .filter(value -> !value.isBlank())
                        .isEmpty()) {
            throw new IllegalStateException("Session token issuance requires audience binding");
        }
    }

    @Override
    public HealthCheckResponse call() {
        final var required = issuanceRequired();
        final var ready = keyRegistry.isReady();
        final var builder = HealthCheckResponse.named("signing-key")
                .withData("required", required)
                .withData("ready", ready)
                .withData("rotation", rotationConfig.enabled());
        return !required || ready ? builder.up().build() : builder.down().build();
    }

    private boolean issuanceRequired() {
        return routeAuthConfig.enabled()
                || (sessionConfig.enabled() && sessionConfig.jws().enabled());
    }

    private boolean signingReady() {
        return !issuanceRequired() || keyRegistry.isReady();
    }
}
