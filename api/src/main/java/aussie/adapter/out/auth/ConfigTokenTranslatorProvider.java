package aussie.adapter.out.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.jboss.logging.Logger;

import aussie.adapter.out.auth.config.TranslationConfigSchema;
import aussie.adapter.out.auth.config.TranslationConfigSchema.ClaimSource;
import aussie.adapter.out.auth.config.TranslationConfigSchema.Operation;
import aussie.core.config.TokenTranslationConfig;
import aussie.core.model.auth.TranslatedClaims;
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
    private volatile TranslationConfigSchema schema;
    private volatile boolean available;

    @Inject
    public ConfigTokenTranslatorProvider(TokenTranslationConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
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
            builder.up()
                    .withData("configVersion", schema.version())
                    .withData("sourcesCount", schema.sources().size())
                    .withData("transformsCount", schema.transforms().size());
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

        final var extracted = extractFromSources(claims);
        final var transformed = applyTransforms(extracted);
        final var result = applyMappings(transformed);

        LOG.debugf(
                "Config translation: issuer=%s, subject=%s, extracted=%s, transformed=%s, roles=%s, permissions=%s",
                issuer, subject, extracted, transformed, result.roles(), result.permissions());

        return Uni.createFrom().item(result);
    }

    private void loadConfig(String path) {
        try {
            final var configPath = Path.of(path);
            if (!Files.exists(configPath)) {
                LOG.warnf("Token translation config file not found: %s", path);
                available = false;
                return;
            }

            final var content = Files.readString(configPath);
            schema = objectMapper.readValue(content, TranslationConfigSchema.class);
            available = true;

            LOG.infof(
                    "Loaded token translation config: version=%d, sources=%d, transforms=%d, mappings=%d",
                    schema.version(),
                    schema.sources().size(),
                    schema.transforms().size(),
                    schema.mappings().roleToPermissions().size()
                            + schema.mappings().directPermissions().size());
        } catch (IOException e) {
            LOG.errorf(e, "Failed to load token translation config from: %s", path);
            available = false;
        }
    }

    /**
     * Extracts values from claims based on configured sources.
     */
    private Map<String, Set<String>> extractFromSources(Map<String, Object> claims) {
        final var result = new HashMap<String, Set<String>>();

        for (final var source : schema.sources()) {
            final var value = getNestedClaim(claims, source.claim());
            if (value != null) {
                result.put(source.name(), parseClaimValue(value, source.type()));
            }
        }

        return result;
    }

    /**
     * Gets a nested claim value using dot notation.
     */
    private Object getNestedClaim(Map<String, Object> claims, String path) {
        final var parts = path.split("\\.");
        Object current = claims;

        for (final var part : parts) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else {
                return null;
            }
        }

        return current;
    }

    /**
     * Parses a claim value based on its type.
     */
    private Set<String> parseClaimValue(Object value, ClaimSource.ClaimType type) {
        return switch (type) {
            case ARRAY -> {
                if (value instanceof List<?> list) {
                    yield list.stream().map(Object::toString).collect(Collectors.toSet());
                }
                yield Set.of();
            }
            case SPACE_DELIMITED -> {
                if (value instanceof String s && !s.isBlank()) {
                    yield Set.of(s.trim().split("\\s+"));
                }
                yield Set.of();
            }
            case COMMA_DELIMITED -> {
                if (value instanceof String s && !s.isBlank()) {
                    yield Set.of(s.split(",")).stream()
                            .map(String::trim)
                            .filter(str -> !str.isEmpty())
                            .collect(Collectors.toSet());
                }
                yield Set.of();
            }
            case SINGLE -> Set.of(value.toString());
        };
    }

    /**
     * Applies configured transforms to extracted values.
     */
    private Map<String, Set<String>> applyTransforms(Map<String, Set<String>> extracted) {
        final var result = new HashMap<>(extracted);

        for (final var transform : schema.transforms()) {
            final var values = result.get(transform.source());
            if (values != null && !values.isEmpty()) {
                var transformed = new HashSet<>(values);
                for (final var operation : transform.operations()) {
                    transformed = applyOperation(transformed, operation);
                }
                result.put(transform.source(), transformed);
            }
        }

        return result;
    }

    /**
     * Applies a single transform operation to a set of values.
     */
    private HashSet<String> applyOperation(Set<String> values, Operation operation) {
        return values.stream()
                .map(value -> applyOperationToValue(value, operation))
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * Applies a transform operation to a single value.
     */
    private String applyOperationToValue(String value, Operation operation) {
        return switch (operation) {
            case Operation.StripPrefix op -> {
                if (value.startsWith(op.value())) {
                    yield value.substring(op.value().length());
                }
                yield value;
            }
            case Operation.Replace op -> value.replace(op.from(), op.to());
            case Operation.Lowercase ignored -> value.toLowerCase();
            case Operation.Uppercase ignored -> value.toUpperCase();
            case Operation.Regex op -> {
                final var pattern = Pattern.compile(op.pattern());
                yield pattern.matcher(value).replaceAll(op.replacement());
            }
        };
    }

    /**
     * Maps transformed values to roles and permissions.
     */
    private TranslatedClaims applyMappings(Map<String, Set<String>> transformed) {
        final var roles = new HashSet<String>();
        final var permissions = new HashSet<String>();

        final var allValues = transformed.values().stream().flatMap(Set::stream).collect(Collectors.toSet());

        final var mappings = schema.mappings();

        for (final var value : allValues) {
            final var rolePermissions = mappings.roleToPermissions().get(value);
            if (rolePermissions != null) {
                roles.add(value);
                permissions.addAll(rolePermissions);
            }

            final var directPermission = mappings.directPermissions().get(value);
            if (directPermission != null) {
                permissions.add(directPermission);
            }
        }

        if (schema.defaults() != null && schema.defaults().includeUnmapped()) {
            for (final var value : allValues) {
                if (!mappings.roleToPermissions().containsKey(value)
                        && !mappings.directPermissions().containsKey(value)) {
                    roles.add(value);
                }
            }
        }

        return new TranslatedClaims(roles, permissions, Map.of());
    }

    /**
     * Reloads the configuration from the configured path.
     * This method is intended for administrative use to refresh config at runtime.
     */
    public void reloadConfig() {
        config.config().path().ifPresent(this::loadConfig);
    }
}
