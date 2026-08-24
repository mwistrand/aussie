package aussie.adapter.in.health;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.Config;

import aussie.core.service.lifecycle.StartupState;

/** Rejects production configurations that would silently weaken a security boundary. */
@ApplicationScoped
public class ProductionConfigurationValidator {

    private final Config config;
    private final StartupState startupState;

    @Inject
    public ProductionConfigurationValidator(Config config, StartupState startupState) {
        this.config = config;
        this.startupState = startupState;
    }

    void onStart(@Observes @Priority(0) StartupEvent event) {
        if (LaunchMode.current() != LaunchMode.NORMAL) {
            return;
        }

        try {
            validate(LaunchMode.NORMAL);
            startupState.complete(StartupState.Phase.CONFIG_VALIDATED);
        } catch (RuntimeException e) {
            startupState.fail(StartupState.Failure.CONFIGURATION_INVALID);
            throw e;
        }
    }

    void validate(LaunchMode launchMode) {
        if (launchMode != LaunchMode.NORMAL || !enabled("aussie.production-configuration-validation.enabled", true)) {
            return;
        }

        requireProvider("aussie.storage.repository.provider");
        requireProvider("aussie.auth.storage.provider");
        requireProvider("aussie.auth.roles.storage.provider");
        requireProvider("aussie.translation-config.storage.provider");

        if (enabled("aussie.session.enabled", true)) {
            requireProvider("aussie.session.storage.provider");
            requireSecureSessionCookie();
        }
        if (enabled("aussie.auth.pkce.enabled", true)) {
            requireProvider("aussie.auth.pkce.storage.provider");
        }
        if (enabled("aussie.storage.cache.enabled", false)) {
            requireProvider("aussie.storage.cache.provider");
        }
        if (enabled("aussie.auth.cache.enabled", false)) {
            requireProvider("aussie.auth.cache.provider");
        }

        validateCors();
        validateProxyTrust();
        validateEgressPolicy();
        validateAuthentication();
        validateEncryption();
        validateRateLimiting();
        validateResiliency();
        validateWebSocket();
        validateTelemetry();
    }

    private void requireProvider(String key) {
        final var provider = value(key);
        if (provider.isEmpty() || provider.get().equalsIgnoreCase("memory")) {
            throw invalid(key + " must explicitly select a durable provider");
        }
    }

    private void requireSecureSessionCookie() {
        if (!enabled("aussie.session.cookie.secure", true)) {
            throw invalid("aussie.session.cookie.secure must be true");
        }
        if (!enabled("aussie.session.cookie.http-only", true)) {
            throw invalid("aussie.session.cookie.http-only must be true");
        }
    }

    private void validateCors() {
        if (!enabled("aussie.gateway.cors.enabled", true)) {
            return;
        }

        final var origins = csv("aussie.gateway.cors.allowed-origins");
        final var credentials = enabled("aussie.gateway.cors.allow-credentials", true);
        if (origins.isEmpty() || (credentials && origins.contains("*"))) {
            throw invalid("credentialed CORS requires an explicit allowed-origin list");
        }
        origins.forEach(origin -> {
            if (origin.contains("*")) {
                throw invalid("CORS origins must be exact origins; wildcards are forbidden");
            }
            try {
                final var parsed = URI.create(origin);
                if (!parsed.isAbsolute()
                        || !parsed.getScheme().equalsIgnoreCase("https")
                        || parsed.getHost() == null
                        || parsed.getUserInfo() != null
                        || !parsed.getPath().isEmpty()
                        || parsed.getQuery() != null
                        || parsed.getFragment() != null
                        || parsed.getPort() > 65535) {
                    throw invalid("production CORS origins must be HTTPS absolute origins");
                }
            } catch (IllegalArgumentException e) {
                throw invalid("CORS origins must be valid absolute origins");
            }
        });
    }

    private void validateProxyTrust() {
        if (enabled("aussie.gateway.trusted-proxy.enabled", false)
                && csv("aussie.gateway.trusted-proxy.proxies").isEmpty()) {
            throw invalid("trusted proxy mode requires an explicit proxy allowlist");
        }
    }

    private void validateEgressPolicy() {
        if (enabled("aussie.gateway.security.allow-private-upstreams", false)
                && csv("aussie.gateway.security.allowed-upstream-hosts").isEmpty()) {
            throw invalid("private upstreams require an explicit upstream host allowlist");
        }
        csv("aussie.gateway.security.allowed-upstream-hosts").forEach(host -> {
            if (host.equals("*") || host.contains("://")) {
                throw invalid("upstream allowlist entries must be host names, not wildcards or URLs");
            }
        });
    }

    private void validateAuthentication() {
        if (enabled("aussie.auth.dangerous-noop", false)) {
            throw invalid("aussie.auth.dangerous-noop is forbidden in production");
        }
        if (enabled("aussie.session.public-creation-enabled", false)
                || enabled("aussie.auth.oidc.public-endpoints-enabled", false)) {
            throw invalid("development-only authentication endpoints are forbidden in production");
        }
        if (!enabled("aussie.auth.route-auth.enabled", false)) {
            return;
        }

        final var providerIssuers = new ArrayList<String>();
        for (final var name : config.getPropertyNames()) {
            if (name.startsWith("aussie.auth.route-auth.providers.") && name.endsWith(".issuer")) {
                providerIssuers.add(name);
            }
        }
        if (providerIssuers.isEmpty()) {
            throw invalid("route authentication requires a configured token provider");
        }
        providerIssuers.forEach(issuer -> {
            final var prefix = issuer.substring(0, issuer.length() - ".issuer".length());
            requireNonBlank(issuer);
            requireNonBlank(prefix + ".jwks-uri");
        });

        if (value("aussie.auth.route-auth.jws.signing-key").isEmpty()
                && !enabled("aussie.auth.key-rotation.enabled", false)) {
            throw invalid("route authentication requires a signing key or durable key rotation");
        }
        if (!enabled("aussie.auth.route-auth.jws.require-audience", true)
                && value("aussie.auth.route-auth.jws.default-audience")
                        .orElse("")
                        .isBlank()) {
            throw invalid("route authentication requires audience binding");
        }
        if (enabled("aussie.auth.token-translation.enabled", false)
                && value("aussie.auth.token-translation.provider")
                        .orElse("default")
                        .equalsIgnoreCase("remote")
                && value("aussie.auth.token-translation.remote.fail-mode")
                        .orElse("deny")
                        .equalsIgnoreCase("allow_empty")) {
            throw invalid("token translation cannot allow empty claims in production");
        }
    }

    private void validateEncryption() {
        final var key = value("aussie.auth.encryption.key").orElse("");
        if (key.isBlank()) {
            throw invalid("aussie.auth.encryption.key must be configured in production");
        }
        try {
            if (Base64.getDecoder().decode(key).length != 32) {
                throw invalid("aussie.auth.encryption.key must decode to 32 bytes");
            }
        } catch (IllegalArgumentException e) {
            throw invalid("aussie.auth.encryption.key must be valid base64");
        }

        if (enabled("aussie.auth.encryption.allow-plaintext-reads", false)) {
            final var expiry = value("aussie.auth.encryption.plaintext-reads-expires-at")
                    .flatMap(this::parseInstant)
                    .orElseThrow(
                            () -> invalid("plaintext-read migration mode requires a future plaintext-reads expiry"));
            if (!expiry.isAfter(Instant.now())) {
                throw invalid("plaintext-read migration mode has expired");
            }
        }
    }

    private void validateRateLimiting() {
        if (!enabled("aussie.rate-limiting.enabled", true)) {
            throw invalid("aussie.rate-limiting.enabled must be true");
        }
        if (!enabled("aussie.rate-limiting.redis.enabled", false)) {
            throw invalid("production rate limiting requires Redis");
        }
        final var fallback = value("aussie.rate-limiting.fallback.behavior")
                .orElseThrow(() -> invalid("rate-limit fallback behavior must be explicit"));
        if (fallback.equalsIgnoreCase("ALLOW")) {
            throw invalid("rate limiting cannot fail open in production");
        }
        if (value("quarkus.redis.hosts").isEmpty()) {
            throw invalid("Redis rate limiting requires explicit Redis hosts");
        }
    }

    private void validateResiliency() {
        final var requestTimeout = duration("aussie.resiliency.http.request-timeout", Duration.ofSeconds(30));
        final var maxRequestTimeout = duration("aussie.resiliency.http.max-request-timeout", Duration.ofMinutes(5));
        final var connectTimeout = duration("aussie.resiliency.http.connect-timeout", Duration.ofSeconds(5));
        if (requestTimeout.isZero() || requestTimeout.isNegative() || requestTimeout.compareTo(maxRequestTimeout) > 0) {
            throw invalid("HTTP request timeout must be positive and within its configured maximum");
        }
        if (connectTimeout.isZero() || connectTimeout.isNegative() || connectTimeout.compareTo(requestTimeout) > 0) {
            throw invalid("HTTP connect timeout must not exceed request timeout");
        }
        final var perHost = integer("aussie.resiliency.http.max-connections-per-host", 50);
        final var total = integer("aussie.resiliency.http.max-connections", 200);
        if (perHost < 1 || total < perHost) {
            throw invalid("HTTP connection limits must be positive and total must cover each host pool");
        }
    }

    private void validateTelemetry() {
        if (!enabled("aussie.telemetry.enabled", false)
                || !enabled("aussie.telemetry.metrics.enabled", false)
                || !enabled("aussie.telemetry.security.enabled", false)) {
            throw invalid("production requires telemetry, metrics, and security telemetry to be enabled");
        }
    }

    private void validateWebSocket() {
        if (integer("aussie.websocket.max-connections", 10_000) < 1
                || integer("aussie.websocket.max-queue-bytes", 1_048_576) < 1) {
            throw invalid("WebSocket connection and queue limits must be positive");
        }
        final var idleTimeout = duration("aussie.websocket.idle-timeout", Duration.ofMinutes(5));
        final var maxLifetime = duration("aussie.websocket.max-lifetime", Duration.ofHours(24));
        if (idleTimeout.toMillis() < 1 || maxLifetime.toMillis() < 1) {
            throw invalid("WebSocket idle timeout and maximum lifetime must be positive");
        }
        if (enabled("aussie.websocket.ping.enabled", true)) {
            final var interval = duration("aussie.websocket.ping.interval", Duration.ofSeconds(30));
            final var timeout = duration("aussie.websocket.ping.timeout", Duration.ofSeconds(10));
            if (interval.toMillis() < 1 || timeout.toMillis() < 1 || timeout.compareTo(interval) >= 0) {
                throw invalid("WebSocket ping timeout must be positive and shorter than its interval");
            }
        }
    }

    private void requireNonBlank(String key) {
        if (value(key).isEmpty()) {
            throw invalid(key + " must be configured");
        }
    }

    private Optional<String> value(String key) {
        return config.getOptionalValue(key, String.class).map(String::trim).filter(value -> !value.isEmpty());
    }

    private boolean enabled(String key, boolean defaultValue) {
        return value(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    private int integer(String key, int defaultValue) {
        return value(key).map(Integer::parseInt).orElse(defaultValue);
    }

    private Duration duration(String key, Duration defaultValue) {
        return value(key).map(Duration::parse).orElse(defaultValue);
    }

    private List<String> csv(String key) {
        return value(key).map(raw -> List.of(raw.split(","))).orElse(List.of()).stream()
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private Optional<Instant> parseInstant(String value) {
        try {
            return Optional.of(Instant.parse(value));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private IllegalStateException invalid(String message) {
        return new IllegalStateException("Invalid production configuration: " + message);
    }
}
