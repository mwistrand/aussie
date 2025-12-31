package aussie.core.model.auth;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import aussie.core.model.auth.TranslationConfigSchema.ClaimSource;
import aussie.core.model.auth.TranslationConfigSchema.Operation;

/**
 * Utility for translating token claims using a configuration schema.
 *
 * <p>Provides the core logic for extracting, transforming, and mapping claims
 * from external IdP tokens to Aussie's authorization model.
 */
public final class ClaimTranslator {

    private ClaimTranslator() {}

    /**
     * Translates claims using the provided schema.
     */
    public static TranslatedClaims translate(TranslationConfigSchema schema, Map<String, Object> claims) {
        final var extracted = extractFromSources(schema, claims);
        final var transformed = applyTransforms(schema, extracted);
        return applyMappings(schema, transformed);
    }

    /**
     * Extracts values from claims based on configured sources.
     */
    public static Map<String, Set<String>> extractFromSources(
            TranslationConfigSchema schema, Map<String, Object> claims) {
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
    public static Object getNestedClaim(Map<String, Object> claims, String path) {
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
    public static Set<String> parseClaimValue(Object value, ClaimSource.ClaimType type) {
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
    public static Map<String, Set<String>> applyTransforms(
            TranslationConfigSchema schema, Map<String, Set<String>> extracted) {
        final var result = new HashMap<>(extracted);

        if (schema.transforms() == null) {
            return result;
        }

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
    public static HashSet<String> applyOperation(Set<String> values, Operation operation) {
        return values.stream()
                .map(value -> applyOperationToValue(value, operation))
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * Applies a transform operation to a single value.
     */
    public static String applyOperationToValue(String value, Operation operation) {
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
    public static TranslatedClaims applyMappings(TranslationConfigSchema schema, Map<String, Set<String>> transformed) {
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
}
