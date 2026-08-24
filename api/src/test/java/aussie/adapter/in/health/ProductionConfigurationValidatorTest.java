package aussie.adapter.in.health;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.LaunchMode;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import aussie.core.service.lifecycle.StartupState;

class ProductionConfigurationValidatorTest {

    @Test
    void acceptsAnExplicitSafeProductionConfiguration() {
        assertDoesNotThrow(() -> validator(safeConfiguration()).validate(LaunchMode.NORMAL));
    }

    @Test
    void rejectsCredentialedWildcardCors() {
        final var configuration = safeConfiguration();
        configuration.put("aussie.gateway.cors.allowed-origins", "*");

        assertThrows(IllegalStateException.class, () -> validator(configuration).validate(LaunchMode.NORMAL));
    }

    @Test
    void rejectsCorsUrlsThatAreNotOrigins() {
        final var configuration = safeConfiguration();
        configuration.put("aussie.gateway.cors.allowed-origins", "https://console.example/callback");

        assertThrows(IllegalStateException.class, () -> validator(configuration).validate(LaunchMode.NORMAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://console.example", "*.example.com"})
    void rejectsInsecureCorsOrigins(String origin) {
        final var configuration = safeConfiguration();
        configuration.put("aussie.gateway.cors.allowed-origins", origin);

        assertThrows(IllegalStateException.class, () -> validator(configuration).validate(LaunchMode.NORMAL));
    }

    @Test
    void rejectsExpiredPlaintextMigrationMode() {
        final var configuration = safeConfiguration();
        configuration.put("aussie.auth.encryption.allow-plaintext-reads", "true");
        configuration.put("aussie.auth.encryption.plaintext-reads-expires-at", "2020-01-01T00:00:00Z");

        assertThrows(IllegalStateException.class, () -> validator(configuration).validate(LaunchMode.NORMAL));
    }

    @Test
    void rejectsInMemoryTranslationConfigurationStorage() {
        final var configuration = safeConfiguration();
        configuration.put("aussie.translation-config.storage.provider", "memory");

        assertThrows(IllegalStateException.class, () -> validator(configuration).validate(LaunchMode.NORMAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aussie.session.cookie.secure", "aussie.session.cookie.http-only"})
    void rejectsInsecureSessionCookies(String property) {
        final var configuration = safeConfiguration();
        configuration.put(property, "false");

        assertThrows(IllegalStateException.class, () -> validator(configuration).validate(LaunchMode.NORMAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aussie.session.public-creation-enabled", "aussie.auth.oidc.public-endpoints-enabled"})
    void rejectsDevelopmentOnlyAuthenticationEndpoints(String property) {
        final var configuration = safeConfiguration();
        configuration.put(property, "true");

        assertThrows(IllegalStateException.class, () -> validator(configuration).validate(LaunchMode.NORMAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"aussie.rate-limiting.enabled", "aussie.rate-limiting.redis.enabled"})
    void requiresDistributedRateLimiting(String property) {
        final var configuration = safeConfiguration();
        configuration.put(property, "false");

        assertThrows(IllegalStateException.class, () -> validator(configuration).validate(LaunchMode.NORMAL));
    }

    @Test
    void requiresSecurityTelemetry() {
        final var configuration = safeConfiguration();
        configuration.put("aussie.telemetry.security.enabled", "false");

        assertThrows(IllegalStateException.class, () -> validator(configuration).validate(LaunchMode.NORMAL));
    }

    @Test
    void ignoresInactiveRemoteTokenTranslationPolicy() {
        final var configuration = safeConfiguration();
        enableRouteAuthentication(configuration);
        configuration.put("aussie.auth.token-translation.enabled", "false");
        configuration.put("aussie.auth.token-translation.provider", "remote");
        configuration.put("aussie.auth.token-translation.remote.fail-mode", "allow_empty");

        assertDoesNotThrow(() -> validator(configuration).validate(LaunchMode.NORMAL));
    }

    @Test
    void rejectsFailOpenRemoteTokenTranslation() {
        final var configuration = safeConfiguration();
        enableRouteAuthentication(configuration);
        configuration.put("aussie.auth.token-translation.enabled", "true");
        configuration.put("aussie.auth.token-translation.provider", "remote");
        configuration.put("aussie.auth.token-translation.remote.fail-mode", "allow_empty");

        assertThrows(IllegalStateException.class, () -> validator(configuration).validate(LaunchMode.NORMAL));
    }

    @Test
    void doesNothingOutsideNormalLaunchMode() {
        assertDoesNotThrow(() -> validator(Map.of()).validate(LaunchMode.TEST));
    }

    @Test
    void skipsValidationWhenExplicitlyDisabled() {
        assertDoesNotThrow(() -> validator(Map.of("aussie.production-configuration-validation.enabled", "false"))
                .validate(LaunchMode.NORMAL));
    }

    private ProductionConfigurationValidator validator(Map<String, String> values) {
        final var config = mock(Config.class);
        when(config.getOptionalValue(anyString(), eq(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(values.get(invocation.getArgument(0))));
        when(config.getPropertyNames()).thenReturn(values.keySet());
        return new ProductionConfigurationValidator(config, new StartupState());
    }

    private void enableRouteAuthentication(Map<String, String> values) {
        values.put("aussie.auth.route-auth.enabled", "true");
        values.put("aussie.auth.route-auth.providers.test.issuer", "https://issuer.example");
        values.put("aussie.auth.route-auth.providers.test.jwks-uri", "https://issuer.example/jwks");
        values.put("aussie.auth.route-auth.jws.signing-key", "configured");
    }

    private Map<String, String> safeConfiguration() {
        final var values = new HashMap<String, String>();
        values.put("aussie.storage.repository.provider", "cassandra");
        values.put("aussie.auth.storage.provider", "cassandra");
        values.put("aussie.auth.roles.storage.provider", "cassandra");
        values.put("aussie.translation-config.storage.provider", "cassandra");
        values.put("aussie.session.enabled", "true");
        values.put("aussie.session.storage.provider", "redis");
        values.put("aussie.session.cookie.secure", "true");
        values.put("aussie.session.cookie.http-only", "true");
        values.put("aussie.session.cookie.same-site", "Lax");
        values.put("aussie.auth.pkce.enabled", "true");
        values.put("aussie.auth.pkce.storage.provider", "redis");
        values.put("aussie.gateway.cors.enabled", "true");
        values.put("aussie.gateway.cors.allowed-origins", "https://console.example");
        values.put("aussie.gateway.cors.allow-credentials", "true");
        values.put("aussie.gateway.trusted-proxy.enabled", "false");
        values.put("aussie.gateway.security.allow-private-upstreams", "false");
        values.put("aussie.auth.dangerous-noop", "false");
        values.put("aussie.session.public-creation-enabled", "false");
        values.put("aussie.auth.oidc.public-endpoints-enabled", "false");
        values.put("aussie.auth.encryption.key", Base64.getEncoder().encodeToString(new byte[32]));
        values.put("aussie.auth.encryption.allow-plaintext-reads", "false");
        values.put("aussie.rate-limiting.enabled", "true");
        values.put("aussie.rate-limiting.fallback.behavior", "DENY");
        values.put("aussie.rate-limiting.redis.enabled", "true");
        values.put("quarkus.redis.hosts", "redis://redis:6379");
        values.put("aussie.resiliency.http.request-timeout", "PT30S");
        values.put("aussie.resiliency.http.max-request-timeout", "PT5M");
        values.put("aussie.resiliency.http.connect-timeout", "PT5S");
        values.put("aussie.resiliency.http.max-connections-per-host", "50");
        values.put("aussie.resiliency.http.max-connections", "200");
        values.put("aussie.telemetry.enabled", "true");
        values.put("aussie.telemetry.metrics.enabled", "true");
        values.put("aussie.telemetry.security.enabled", "true");
        return values;
    }
}
