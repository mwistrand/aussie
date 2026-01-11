package aussie.adapter.out.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import aussie.adapter.out.telemetry.TokenTranslationMetrics;
import aussie.core.config.TokenTranslationConfig;
import aussie.core.model.auth.ClaimTranslator;
import aussie.core.model.auth.TranslatedClaims;
import aussie.core.model.auth.TranslationConfigSchema;
import aussie.spi.TokenTranslatorProvider;

/**
 * Configuration-driven token translator.
 *
 * <p>This provider uses a JSON configuration file to define how claims are
 * extracted from external IdP tokens and mapped to Aussie's authorization model.
 *
 * <p>Configuration is read from the path specified in
 * {@code aussie.auth.token-translation.config.path}.
 */
@ApplicationScoped
public class ConfigTokenTranslatorProvider implements TokenTranslatorProvider {

    private static final Logger LOG = Logger.getLogger(ConfigTokenTranslatorProvider.class);
    private static final String NAME = "config";
    private static final int PRIORITY = 50;

    private final TokenTranslationConfig config;
    private final ObjectMapper objectMapper;
    private final TokenTranslationMetrics metrics;
    private volatile TranslationConfigSchema schema;
    private volatile boolean available;

    @Inject
    public ConfigTokenTranslatorProvider(
            TokenTranslationConfig config, ObjectMapper objectMapper, TokenTranslationMetrics metrics) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @PostConstruct
    void init() {
        config.config().path().ifPresent(this::loadConfig);
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
        return available && schema != null;
    }

    @Override
    public Optional<HealthCheckResponse> healthCheck() {
        final var builder = HealthCheckResponse.named("token-translator-config").withData("provider", NAME);

        if (available && schema != null) {
            final int sourcesCount = schema.sources() != null ? schema.sources().size() : 0;
            final int transformsCount =
                    schema.transforms() != null ? schema.transforms().size() : 0;
            builder.up()
                    .withData("configVersion", schema.version())
                    .withData("sourcesCount", sourcesCount)
                    .withData("transformsCount", transformsCount);
        } else {
            builder.down().withData("reason", "Configuration not loaded");
        }

        return Optional.of(builder.build());
    }

    @Override
    public Uni<TranslatedClaims> translate(String issuer, String subject, Map<String, Object> claims) {
        if (schema == null) {
            LOG.warnf("Config translation requested but no schema loaded: issuer=%s, subject=%s", issuer, subject);
            return Uni.createFrom().item(TranslatedClaims.empty());
        }

        final var result = ClaimTranslator.translate(schema, claims);

        LOG.debugf(
                "Config translation: issuer=%s, subject=%s, roles=%s, permissions=%s",
                issuer, subject, result.roles(), result.permissions());

        return Uni.createFrom().item(result);
    }

    private void loadConfig(String path) {
        final var startTime = System.currentTimeMillis();
        try {
            final var configPath = Path.of(path);
            if (!Files.exists(configPath)) {
                LOG.warnf("Token translation config file not found: path=%s", path);
                available = false;
                metrics.recordConfigReload(false);
                return;
            }

            final var content = Files.readString(configPath);
            schema = objectMapper.readValue(content, TranslationConfigSchema.class);
            available = true;

            final var duration = System.currentTimeMillis() - startTime;
            metrics.recordConfigReload(true);

            final int sourcesCount = schema.sources() != null ? schema.sources().size() : 0;
            final int transformsCount =
                    schema.transforms() != null ? schema.transforms().size() : 0;
            final int mappingsCount = getMappingsCount(schema);

            LOG.infof(
                    "Loaded token translation config: path=%s, version=%d, sources=%d, transforms=%d, mappings=%d, duration=%dms",
                    path, schema.version(), sourcesCount, transformsCount, mappingsCount, duration);
        } catch (IOException e) {
            final var duration = System.currentTimeMillis() - startTime;
            metrics.recordConfigReload(false);
            LOG.errorf(e, "Failed to load token translation config: path=%s, duration=%dms", path, duration);
            available = false;
        }
    }

    /**
     * Reloads the configuration from the configured path.
     */
    public void reloadConfig() {
        config.config().path().ifPresent(this::loadConfig);
    }

    private int getMappingsCount(TranslationConfigSchema schema) {
        if (schema.mappings() == null) {
            return 0;
        }
        final int roleToPermissions = schema.mappings().roleToPermissions() != null
                ? schema.mappings().roleToPermissions().size()
                : 0;
        final int directPermissions = schema.mappings().directPermissions() != null
                ? schema.mappings().directPermissions().size()
                : 0;
        return roleToPermissions + directPermissions;
    }
}
