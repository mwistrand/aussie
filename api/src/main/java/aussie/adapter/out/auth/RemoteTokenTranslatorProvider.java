package aussie.adapter.out.auth;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import aussie.adapter.out.telemetry.TokenTranslationMetrics;
import aussie.core.config.TokenTranslationConfig;
import aussie.core.config.TokenTranslationConfig.Remote.FailMode;
import aussie.core.model.auth.TranslatedClaims;
import aussie.spi.TokenTranslatorProvider;

/**
 * Remote token translator that delegates to an external HTTP service.
 *
 * <p>This provider calls an external service to translate token claims.
 * The service must accept POST requests with JSON body and return translated
 * roles and permissions.
 *
 * <h2>Request Format</h2>
 * <pre>{@code
 * POST /translate
 * Content-Type: application/json
 *
 * {
 *   "issuer": "https://auth.example.com",
 *   "subject": "user-123",
 *   "claims": { ... }
 * }
 * }</pre>
 *
 * <h2>Response Format</h2>
 * <pre>{@code
 * {
 *   "roles": ["admin", "user"],
 *   "permissions": ["read", "write"]
 * }
 * }</pre>
 */
@ApplicationScoped
public class RemoteTokenTranslatorProvider implements TokenTranslatorProvider {

    private static final Logger LOG = Logger.getLogger(RemoteTokenTranslatorProvider.class);
    private static final String NAME = "remote";
    private static final int PRIORITY = 25;

    private final WebClient webClient;
    private final TokenTranslationConfig config;
    private final TokenTranslationMetrics metrics;

    @Inject
    public RemoteTokenTranslatorProvider(Vertx vertx, TokenTranslationConfig config, TokenTranslationMetrics metrics) {
        this.webClient = WebClient.create(vertx);
        this.config = config;
        this.metrics = metrics;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public boolean isAvailable() {
        return config.remote().url().isPresent() && !config.remote().url().get().isBlank();
    }

    @Override
    public Optional<HealthCheckResponse> healthCheck() {
        final var url = config.remote().url();
        if (url.isPresent() && !url.get().isBlank()) {
            return Optional.of(HealthCheckResponse.named("token-translator-remote")
                    .up()
                    .withData("provider", NAME)
                    .withData("url", url.get())
                    .withData("timeout", config.remote().timeout().toString())
                    .build());
        } else {
            return Optional.of(HealthCheckResponse.named("token-translator-remote")
                    .down()
                    .withData("provider", NAME)
                    .withData("reason", "URL not configured")
                    .build());
        }
    }

    @Override
    public Uni<TranslatedClaims> translate(String issuer, String subject, Map<String, Object> claims) {
        final var url = config.remote().url().orElseThrow(() -> new IllegalStateException("Remote URL not configured"));
        final var startTime = System.currentTimeMillis();

        LOG.debugf("Calling remote translation service: url=%s, issuer=%s, subject=%s", url, issuer, subject);

        final var request =
                new JsonObject().put("issuer", issuer).put("subject", subject).put("claims", new JsonObject(claims));

        final var timeout = config.remote().timeout().toMillis();

        return webClient
                .postAbs(url)
                .timeout(timeout)
                .putHeader("Content-Type", "application/json")
                .putHeader("Accept", "application/json")
                .sendJsonObject(request)
                .map(response -> {
                    final var duration = System.currentTimeMillis() - startTime;
                    metrics.recordRemoteCall(response.statusCode(), duration);

                    if (response.statusCode() != 200) {
                        LOG.warnf(
                                "Remote translation failed: url=%s, status=%d, duration=%dms, body=%s",
                                url, response.statusCode(), duration, response.bodyAsString());
                        throw new RemoteTranslationException(
                                "Remote translation service returned status " + response.statusCode());
                    }

                    final var result = parseResponse(response.bodyAsJsonObject());
                    LOG.debugf(
                            "Remote translation success: url=%s, issuer=%s, subject=%s, roles=%d, permissions=%d, duration=%dms",
                            url,
                            issuer,
                            subject,
                            result.roles().size(),
                            result.permissions().size(),
                            duration);
                    return result;
                })
                .onFailure()
                .invoke(error -> {
                    final var duration = System.currentTimeMillis() - startTime;
                    metrics.recordRemoteCall(0, duration);
                    metrics.recordError(NAME, error.getClass().getSimpleName());
                    LOG.warnf(error, "Remote translation error: url=%s, duration=%dms", url, duration);
                })
                .onFailure()
                .recoverWithItem(this::handleFailure);
    }

    private TranslatedClaims parseResponse(JsonObject json) {
        final var roles = extractStringSet(json, "roles");
        final var permissions = extractStringSet(json, "permissions");

        LOG.debugf("Remote translation response: roles=%s, permissions=%s", roles, permissions);

        return new TranslatedClaims(roles, permissions, Map.of());
    }

    private Set<String> extractStringSet(JsonObject json, String field) {
        final var array = json.getJsonArray(field);
        if (array == null) {
            return Set.of();
        }

        final var result = new HashSet<String>();
        for (int i = 0; i < array.size(); i++) {
            final var value = array.getValue(i);
            if (value != null) {
                result.add(value.toString());
            }
        }
        return result;
    }

    private TranslatedClaims handleFailure(Throwable error) {
        LOG.warnf(error, "Remote token translation failed");

        if (config.remote().failMode() == FailMode.allow_empty) {
            LOG.debugf("Returning empty claims due to fail mode: allow_empty");
            return TranslatedClaims.empty();
        }

        throw new RemoteTranslationException("Remote token translation failed: " + error.getMessage(), error);
    }

    /**
     * Exception thrown when remote translation fails.
     */
    public static class RemoteTranslationException extends RuntimeException {
        public RemoteTranslationException(String message) {
            super(message);
        }

        public RemoteTranslationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
